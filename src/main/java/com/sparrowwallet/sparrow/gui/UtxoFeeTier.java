package com.sparrowwallet.sparrow.gui;

/**
 * Fee-impact severity tiers for the UTXO treemap heatmap.
 *
 * <p>A UTXO's "fee impact" is the share of its value that would be spent on the
 * miner fee just to include it as an input at a given fee rate
 * ({@code inputVbytes * feeRate / value}). Small outputs become progressively
 * uneconomical — and eventually unspendable ("dust") — as the fee rate rises.
 *
 * <p>Thresholds and the green→red palette are adapted from the utxo-modeller
 * project (https://github.com/maxtannahill/utxo-modeller). Colours are applied
 * via the matching {@code tier-1 … tier-6} style classes in {@code ashigaru.css}.
 */
public enum UtxoFeeTier {
    OPTIMAL     (0.01,          "Optimal",     "< 0.01% fee",        "tier-1"),
    ECONOMICAL  (0.1,           "Economical",  "0.01–0.1% fee",      "tier-2"),
    MARGINAL    (1.0,           "Marginal",    "0.1–1% fee",         "tier-3"),
    EXPENSIVE   (10.0,          "Expensive",   "1–10% fee",          "tier-4"),
    UNECONOMICAL(99.99,         "Uneconomical","10–99.99% fee",      "tier-5"),
    UNSPENDABLE (Double.MAX_VALUE, "Unspendable", "≥ 99.99% fee (dust)", "tier-6");

    private final double maxPercentExclusive;
    private final String label;
    private final String rangeDescription;
    private final String styleClass;

    UtxoFeeTier(double maxPercentExclusive, String label, String rangeDescription, String styleClass) {
        this.maxPercentExclusive = maxPercentExclusive;
        this.label = label;
        this.rangeDescription = rangeDescription;
        this.styleClass = styleClass;
    }

    /** Display name, e.g. "Unspendable". */
    public String getLabel() {
        return label;
    }

    /** Human-readable range, e.g. "1–10% fee". */
    public String getRangeDescription() {
        return rangeDescription;
    }

    /** CSS style class ("tier-1" … "tier-6") applied to tiles and group containers. */
    public String getStyleClass() {
        return styleClass;
    }

    /**
     * Maps a fee-impact percentage to its tier. A percentage is placed in the
     * first tier whose upper bound it falls below; anything at or above 99.99%
     * (or non-positive input, i.e. a valueless UTXO) is {@link #UNSPENDABLE}.
     */
    public static UtxoFeeTier of(double feeImpactPercent) {
        if(feeImpactPercent <= 0 || Double.isNaN(feeImpactPercent)) {
            return OPTIMAL;
        }
        for(UtxoFeeTier tier : values()) {
            if(feeImpactPercent < tier.maxPercentExclusive) {
                return tier;
            }
        }
        return UNSPENDABLE;
    }
}
