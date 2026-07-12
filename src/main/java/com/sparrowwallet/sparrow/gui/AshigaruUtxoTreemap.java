package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.sparrow.UnitFormat;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.wallet.UtxoEntry;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.controlsfx.control.PopOver;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A squarified, fee-impact-heatmap treemap of a wallet's UTXOs — the Ashigaru
 * account view's primary UTXO surface (replacing the old one-card-per-UTXO list).
 *
 * <p>Each UTXO is a rectangle whose <b>area is proportional to its BTC value</b>
 * and whose <b>colour encodes its fee-impact tier</b> ({@link UtxoFeeTier}) at the
 * currently modelled fee rate — green (economical) through red (unspendable dust).
 * Tiles are grouped under six labelled severity groups. Presentation model adapted
 * from https://github.com/maxtannahill/utxo-modeller.
 *
 * <p>Selection is driven purely through {@code UtxoRow.selected}, so the existing
 * Select-All / Mix-Selected plumbing in {@link AshigaruWalletController} works
 * unchanged. Clicking a tile toggles its selection; hover shows a tooltip;
 * double-click / right-click opens a details popover (label editing + copy).
 */
public class AshigaruUtxoTreemap extends Region {
    private static final double GAP = 2;           // px inset between tiles / groups
    private static final double MIN_LABEL_W = 54;   // hide value label below this tile size
    private static final double MIN_LABEL_H = 26;

    private ObservableList<AshigaruWalletController.UtxoRow> rows;
    private ObservableValue<Boolean> showMixInfo;
    private double feeRate = 1.0;

    private final List<TierGroup> groups = new ArrayList<>();

    private final ListChangeListener<AshigaruWalletController.UtxoRow> rowsListener = c -> rebuild();

    public AshigaruUtxoTreemap() {
        getStyleClass().add("utxo-treemap");
        setMinSize(0, 0);
    }

    public void setItems(ObservableList<AshigaruWalletController.UtxoRow> items) {
        if(rows != null) {
            rows.removeListener(rowsListener);
        }
        rows = items;
        if(rows != null) {
            rows.addListener(rowsListener);
        }
        rebuild();
    }

    public void setShowMixInfo(ObservableValue<Boolean> showMixInfo) {
        this.showMixInfo = showMixInfo;
        if(showMixInfo != null) {
            showMixInfo.addListener((o, a, b) -> refresh());
        }
    }

    /** Sets the modelled fee rate (sats/vB) used for the heatmap and regroups tiles. */
    public void setFeeRate(double feeRate) {
        if(feeRate > 0 && feeRate != this.feeRate) {
            this.feeRate = feeRate;
            rebuild();
        }
    }

    /** Re-reads mix status / selection on existing tiles without regrouping. */
    public void refresh() {
        for(TierGroup group : groups) {
            group.refreshTiles();
        }
    }

    private void rebuild() {
        for(TierGroup group : groups) {
            group.dispose();
        }
        groups.clear();
        getChildren().clear();

        if(rows == null || rows.isEmpty()) {
            return;
        }

        Map<UtxoFeeTier, List<AshigaruWalletController.UtxoRow>> byTier = new EnumMap<>(UtxoFeeTier.class);
        for(AshigaruWalletController.UtxoRow row : rows) {
            UtxoFeeTier tier = UtxoFeeTier.of(feeImpactPercent(row, feeRate));
            byTier.computeIfAbsent(tier, t -> new ArrayList<>()).add(row);
        }

        // Ordered Optimal -> Unspendable so the healthy end reads first.
        for(UtxoFeeTier tier : UtxoFeeTier.values()) {
            List<AshigaruWalletController.UtxoRow> tierRows = byTier.get(tier);
            if(tierRows != null && !tierRows.isEmpty()) {
                TierGroup group = new TierGroup(tier, tierRows, showMixInfo, feeRate);
                groups.add(group);
                getChildren().add(group);
            }
        }
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        if(groups.isEmpty() || w <= 0 || h <= 0) {
            return;
        }

        double totalValue = 0;
        for(TierGroup group : groups) {
            totalValue += group.getTotalValue();
        }
        if(totalValue <= 0) {
            return;
        }

        double[] areas = new double[groups.size()];
        double totalArea = w * h;
        for(int i = 0; i < groups.size(); i++) {
            areas[i] = groups.get(i).getTotalValue() / totalValue * totalArea;
        }

        Rect[] out = squarify(areas, 0, 0, w, h);
        for(int i = 0; i < groups.size(); i++) {
            Rect r = out[i];
            groups.get(i).resizeRelocate(r.x + GAP, r.y + GAP,
                    Math.max(0, r.w - 2 * GAP), Math.max(0, r.h - 2 * GAP));
        }
    }

    // -------------------------------------------------------------------------
    // Fee-impact model
    // -------------------------------------------------------------------------

    static double feeImpactPercent(AshigaruWalletController.UtxoRow row, double feeRate) {
        Long value = row.utxoEntry.getValue();
        if(value == null || value <= 0) {
            return Double.MAX_VALUE;
        }
        return feeRate * inputVbytes(row.utxoEntry) / value * 100.0;
    }

    static double inputVbytes(UtxoEntry entry) {
        ScriptType scriptType = null;
        try {
            Address address = entry.getAddress();
            if(address != null) {
                scriptType = address.getScriptType();
            }
        } catch(Exception e) {
            // fall through to wallet script type
        }
        if(scriptType == null) {
            try {
                scriptType = entry.getWallet().getScriptType();
            } catch(Exception e) {
                // fall through to default
            }
        }
        if(scriptType == null) {
            scriptType = ScriptType.P2WPKH;
        }
        return scriptType.getInputVbytes();
    }

    // -------------------------------------------------------------------------
    // Squarified treemap layout (Bruls, Huizing & van Wijk 2000)
    // -------------------------------------------------------------------------

    /**
     * Lays out {@code areas} (already scaled so their sum equals {@code w*h}) into
     * the rectangle at (x, y, w, h), returning one {@link Rect} per input index.
     * Tiles are grouped into rows along the shorter side to keep aspect ratios low.
     */
    static Rect[] squarify(double[] areas, double x, double y, double w, double h) {
        int n = areas.length;
        Rect[] out = new Rect[n];

        // Process indices in descending area order for the best aspect ratios.
        Integer[] order = new Integer[n];
        for(int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(areas[b], areas[a]));

        double cx = x, cy = y, cw = w, ch = h;
        int start = 0;
        while(start < n) {
            if(cw <= 0 || ch <= 0) {
                // Degenerate remainder — collapse the rest to zero-size rects.
                for(int k = start; k < n; k++) {
                    out[order[k]] = new Rect(cx, cy, 0, 0);
                }
                break;
            }
            double shortSide = Math.min(cw, ch);

            // Grow a row while the worst aspect ratio keeps improving.
            double sum = 0, rmin = Double.MAX_VALUE, rmax = 0;
            double curWorst = Double.MAX_VALUE;
            int end = start;
            while(end < n) {
                double a = Math.max(areas[order[end]], 0);
                double nsum = sum + a;
                double nmin = Math.min(rmin, a);
                double nmax = Math.max(rmax, a);
                double worst = worstRatio(nsum, nmin, nmax, shortSide);
                if(end == start || worst <= curWorst) {
                    sum = nsum;
                    rmin = nmin;
                    rmax = nmax;
                    curWorst = worst;
                    end++;
                } else {
                    break;
                }
            }

            double rowArea = sum;
            double thickness = rowArea > 0 ? rowArea / shortSide : 0;
            double pos = 0;
            boolean columnStrip = cw >= ch;  // strip runs down the left / across the top
            for(int k = start; k < end; k++) {
                double a = Math.max(areas[order[k]], 0);
                double frac = rowArea > 0 ? a / rowArea : 0;
                if(columnStrip) {
                    double th = frac * ch;
                    out[order[k]] = new Rect(cx, cy + pos, thickness, th);
                    pos += th;
                } else {
                    double tw = frac * cw;
                    out[order[k]] = new Rect(cx + pos, cy, tw, thickness);
                    pos += tw;
                }
            }

            if(columnStrip) {
                cx += thickness;
                cw -= thickness;
            } else {
                cy += thickness;
                ch -= thickness;
            }
            start = end;
        }
        return out;
    }

    private static double worstRatio(double sum, double rmin, double rmax, double side) {
        if(sum <= 0 || side <= 0) {
            return Double.MAX_VALUE;
        }
        double side2 = side * side;
        double sum2 = sum * sum;
        return Math.max(side2 * rmax / sum2, sum2 / (side2 * rmin));
    }

    static String formatBtc(long sats) {
        UnitFormat fmt = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();
        return fmt.formatBtcValue(sats);
    }

    static String formatFeePercent(double pct) {
        if(pct >= 99.99) {
            return "≥ 99.99%";
        }
        if(pct < 0.01) {
            return "< 0.01%";
        }
        return String.format(Locale.ROOT, pct < 1 ? "%.3f%%" : "%.2f%%", pct);
    }

    static String shortenOutpoint(String outpoint) {
        if(outpoint == null) {
            return "";
        }
        int colon = outpoint.lastIndexOf(':');
        String hash = colon > 0 ? outpoint.substring(0, colon) : outpoint;
        String idx = colon > 0 ? outpoint.substring(colon) : "";
        if(hash.length() > 16) {
            hash = hash.substring(0, 8) + "…" + hash.substring(hash.length() - 4);
        }
        return hash + idx;
    }

    /** Immutable layout rectangle. */
    static final class Rect {
        final double x, y, w, h;
        Rect(double x, double y, double w, double h) {
            this.x = x; this.y = y; this.w = w; this.h = h;
        }
    }

    // -------------------------------------------------------------------------
    // Tier group — a labelled container squarifying its own UTXO tiles
    // -------------------------------------------------------------------------

    private static final class TierGroup extends Region {
        private static final double HEADER_HEIGHT = 22;

        private final List<Tile> tiles = new ArrayList<>();
        private final Label header = new Label();
        private long totalValue;

        TierGroup(UtxoFeeTier tier, List<AshigaruWalletController.UtxoRow> tierRows,
                  ObservableValue<Boolean> showMixInfo, double feeRate) {
            getStyleClass().addAll("tier-group", tier.getStyleClass());
            setMinSize(0, 0);

            header.getStyleClass().add("tier-group-header");
            header.setMaxWidth(Double.MAX_VALUE);
            getChildren().add(header);

            for(AshigaruWalletController.UtxoRow row : tierRows) {
                Long value = row.utxoEntry.getValue();
                totalValue += value == null ? 0 : value;
                Tile tile = new Tile(row, tier, showMixInfo, feeRate);
                tiles.add(tile);
                getChildren().add(tile);
            }

            header.setText(tier.getLabel().toUpperCase(Locale.ROOT)
                    + "  ·  " + tiles.size()
                    + "  ·  " + formatBtc(totalValue) + " BTC");
        }

        long getTotalValue() {
            return totalValue;
        }

        void dispose() {
            for(Tile tile : tiles) {
                tile.dispose();
            }
        }

        void refreshTiles() {
            for(Tile tile : tiles) {
                tile.refresh();
            }
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth();
            double h = getHeight();

            boolean showHeader = h > HEADER_HEIGHT + 10 && w > 40;
            header.setVisible(showHeader);
            header.setManaged(false);
            double top = 0;
            if(showHeader) {
                header.resizeRelocate(6, 0, Math.max(0, w - 12), HEADER_HEIGHT);
                top = HEADER_HEIGHT;
            }

            double innerH = h - top;
            if(tiles.isEmpty() || w <= 0 || innerH <= 0) {
                for(Tile tile : tiles) {
                    tile.setVisible(false);
                }
                return;
            }

            double totV = 0;
            for(Tile tile : tiles) {
                totV += tile.getValue();
            }
            if(totV <= 0) {
                return;
            }

            double[] areas = new double[tiles.size()];
            double totalArea = w * innerH;
            for(int i = 0; i < tiles.size(); i++) {
                areas[i] = tiles.get(i).getValue() / totV * totalArea;
            }

            Rect[] out = squarify(areas, 0, top, w, innerH);
            for(int i = 0; i < tiles.size(); i++) {
                Tile tile = tiles.get(i);
                Rect r = out[i];
                tile.setVisible(true);
                tile.resizeRelocate(r.x + GAP, r.y + GAP,
                        Math.max(0, r.w - 2 * GAP), Math.max(0, r.h - 2 * GAP));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tile — one UTXO
    // -------------------------------------------------------------------------

    private static final class Tile extends StackPane {
        // Animated Whirlpool logo filling the block while a UTXO is actively mixing.
        // White-on-transparent, so the tile's tier colour still shows through.
        private static final Image WHIRLPOOL_GIF =
                new Image("/image/Ashigaru_Whirlpool_Logo_GIF_White_On_Transparent.gif", 320, 320, true, true);

        private final AshigaruWalletController.UtxoRow row;
        private final ObservableValue<Boolean> showMixInfo;
        private final double feeRate;
        private final Label valueLabel = new Label();
        private final Label labelText = new Label();
        private final Region mixDot = new Region();
        private final ImageView mixIcon = new ImageView(WHIRLPOOL_GIF);
        private final Tooltip tooltip = new Tooltip();
        private final ChangeListener<Boolean> selectionListener;
        private final ChangeListener<String> labelListener;
        private boolean hasLabel;

        Tile(AshigaruWalletController.UtxoRow row, UtxoFeeTier tier,
             ObservableValue<Boolean> showMixInfo, double feeRate) {
            this.row = row;
            this.showMixInfo = showMixInfo;
            this.feeRate = feeRate;

            getStyleClass().addAll("utxo-tile", tier.getStyleClass());
            setMinSize(0, 0);

            // Background layer (behind the text): the mixing logo, sized to fill in layoutChildren.
            mixIcon.setMouseTransparent(true);
            mixIcon.setPreserveRatio(true);
            getChildren().add(mixIcon);

            // Centered value + optional label, on top of the background.
            valueLabel.getStyleClass().add("utxo-tile-value");
            valueLabel.setText(row.value);
            labelText.getStyleClass().add("utxo-tile-label");
            VBox centerBox = new VBox(1, valueLabel, labelText);
            centerBox.setAlignment(Pos.CENTER);
            centerBox.setMouseTransparent(true);
            getChildren().add(centerBox);

            // Mixed / unmixed corner dot (hidden while the mixing logo is showing).
            mixDot.getStyleClass().add("utxo-tile-mixdot");
            mixDot.setMouseTransparent(true);
            StackPane.setAlignment(mixDot, Pos.TOP_RIGHT);
            StackPane.setMargin(mixDot, new Insets(4, 4, 0, 0));
            getChildren().add(mixDot);

            tooltip.setShowDelay(Duration.millis(200));
            Tooltip.install(this, tooltip);

            setOnMouseClicked(e -> {
                if(e.getButton() == MouseButton.SECONDARY) {
                    showDetails();
                } else if(e.getButton() == MouseButton.PRIMARY) {
                    if(e.getClickCount() >= 2) {
                        showDetails();
                    } else {
                        row.selected.set(!row.selected.get());
                    }
                }
            });

            selectionListener = (o, a, b) -> updateSelectionStyle();
            row.selected.addListener(selectionListener);
            // Keep the on-tile label in sync when edited (via the details popover or elsewhere).
            labelListener = (o, a, b) -> updateLabel();
            row.utxoEntry.labelProperty().addListener(labelListener);
            updateSelectionStyle();
            updateLabel();
            refresh();
        }

        long getValue() {
            Long value = row.utxoEntry.getValue();
            return value == null ? 0 : value;
        }

        void dispose() {
            row.selected.removeListener(selectionListener);
            row.utxoEntry.labelProperty().removeListener(labelListener);
        }

        private void updateLabel() {
            String lbl = row.utxoEntry.getLabel();
            hasLabel = lbl != null && !lbl.isEmpty();
            labelText.setText(hasLabel ? lbl : "");
            labelText.setManaged(hasLabel);
            requestLayout();
        }

        private void updateSelectionStyle() {
            boolean selected = row.selected.get();
            if(selected && !getStyleClass().contains("selected")) {
                getStyleClass().add("selected");
            } else if(!selected) {
                getStyleClass().remove("selected");
            }
        }

        void refresh() {
            mixDot.getStyleClass().removeAll("mix-done", "mix-unmixed");
            boolean show = showMixInfo != null && Boolean.TRUE.equals(showMixInfo.getValue());
            // While actively mixing, fill the block with the animated Whirlpool logo;
            // otherwise show a small mixed/unmixed dot in the top-right corner.
            UtxoEntry.MixStatus mixStatus = show ? row.utxoEntry.getMixStatus() : null;
            boolean mixing = show && mixStatus != null
                    && (mixStatus.getMixProgress() != null || mixStatus.getNextMixUtxo() != null);

            mixIcon.setVisible(mixing);
            if(!show) {
                mixDot.setVisible(false);
            } else if(mixing) {
                mixDot.setVisible(false);
            } else {
                mixDot.setVisible(true);
                mixDot.getStyleClass().add(mixStatus != null ? "mix-done" : "mix-unmixed");
            }
            tooltip.setText(buildDetailText());
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth();
            double h = getHeight();
            boolean big = w >= MIN_LABEL_W && h >= MIN_LABEL_H;
            valueLabel.setVisible(big);
            labelText.setVisible(big && hasLabel && h >= 44);
            // Fill the block with the mixing logo (contain, centred by the StackPane).
            double side = Math.min(w, h) * 0.92;
            mixIcon.setFitWidth(side);
            mixIcon.setFitHeight(side);
            super.layoutChildren();
        }

        private String buildDetailText() {
            double pct = feeImpactPercent(row, feeRate);
            UtxoFeeTier tier = UtxoFeeTier.of(pct);
            long cost = Math.round(feeRate * inputVbytes(row.utxoEntry));
            StringBuilder sb = new StringBuilder();
            sb.append(row.value).append(" (").append(String.format(Locale.ROOT, "%,d", getValue())).append(" sats)\n");
            sb.append(tier.getLabel()).append(" — ").append(formatFeePercent(pct))
                    .append(" fee impact (~").append(String.format(Locale.ROOT, "%,d", cost)).append(" sats to spend)\n");
            if(row.address != null && !row.address.isEmpty()) {
                sb.append(row.address).append("\n");
            }
            sb.append(shortenOutpoint(row.output)).append("\n");
            sb.append(row.date);
            String stage = AshigaruWalletController.getMixStage(row.utxoEntry.getMixStatus());
            if(stage != null && !stage.isEmpty()) {
                sb.append("\n").append(stage);
            }
            return sb.toString();
        }

        private void showDetails() {
            double pct = feeImpactPercent(row, feeRate);
            UtxoFeeTier tier = UtxoFeeTier.of(pct);

            VBox content = new VBox(8);
            content.getStyleClass().add("utxo-detail-popover");
            content.setPadding(new Insets(14));

            Label valueLabel = new Label(row.value);
            valueLabel.getStyleClass().add("card-value");

            Label feeLabel = new Label(tier.getLabel() + "  ·  " + formatFeePercent(pct) + " fee impact");
            feeLabel.getStyleClass().add("card-secondary");

            TextField labelField = new TextField(row.utxoEntry.getLabel() == null ? "" : row.utxoEntry.getLabel());
            labelField.setPromptText("Add label…");
            labelField.getStyleClass().add("card-label-field");
            Runnable commit = () -> row.utxoEntry.labelProperty().set(labelField.getText() == null ? "" : labelField.getText());
            labelField.setOnAction(e -> commit.run());
            labelField.focusedProperty().addListener((o, was, is) -> { if(was && !is) commit.run(); });

            content.getChildren().addAll(valueLabel, feeLabel, labelField);

            if(row.address != null && !row.address.isEmpty()) {
                Label addr = new Label(row.address);
                addr.getStyleClass().add("card-secondary");
                content.getChildren().add(new HBox(6, addr, AshigaruWalletController.makeCopyButton(row.address)));
            }
            if(row.output != null && !row.output.isEmpty()) {
                Label op = new Label(shortenOutpoint(row.output));
                op.getStyleClass().add("card-faint");
                content.getChildren().add(new HBox(6, op, AshigaruWalletController.makeCopyButton(row.output)));
            }
            Label date = new Label(row.date);
            date.getStyleClass().add("card-faint");
            content.getChildren().add(date);

            // The PopOver renders in its own popup scene, so carry the app theme
            // onto the content node's own stylesheets (styles the whole subtree).
            if(getScene() != null) {
                content.getStylesheets().addAll(getScene().getStylesheets());
            }

            PopOver popOver = new PopOver(content);
            popOver.setDetachable(false);
            popOver.setArrowLocation(PopOver.ArrowLocation.TOP_CENTER);
            popOver.show(this);
        }
    }
}
