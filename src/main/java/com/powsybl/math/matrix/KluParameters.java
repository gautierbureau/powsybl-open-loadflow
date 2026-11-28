/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.math.matrix;

import com.powsybl.math.AbstractMathNative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;

/**
 * The parameters of the KLU decompositions of the native math library, for the whole process: those created after
 * {@link #apply()} take them. It needs a powsybl-math-native that has them: {@link #isAvailable()} tells.
 *
 * <p>For tests, {@link #applySystemProperties()} reads them from the {@code olf.klu.*} system properties: ordering
 * (amd, colamd, minimum_fill), btf (true, false), scale (-1, 0, 1, 2), tol, check (rgrowth, rcond, none) and
 * rcondThreshold.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class KluParameters extends AbstractMathNative {

    private static final Logger LOGGER = LoggerFactory.getLogger(KluParameters.class);

    /** The fill-reducing order of each block. */
    public enum Ordering {
        AMD,
        COLAMD,
        /**
         * Tinney's scheme 3, the elimination that adds the fewest edges first, or AMD, the one whose factorization has
         * the fewer operations (AMD without trying the minimum fill when a row is dense). Some 30 times longer to
         * compute than AMD: for a pattern factorized many times.
         */
        MINIMUM_FILL
    }

    /** The check of a refactorization on the pivots of the previous factorization, before a whole factorization. */
    public enum Check {
        /** The reciprocal pivot growth, against the threshold of the matrix: a pass on all the values. */
        RGROWTH,
        /** The estimate of the reciprocal condition number of the diagonal of U, against rcondThreshold. */
        RCOND,
        /** None: a refactorization that does not fail is kept. */
        NONE
    }

    public static final String PROPERTY_PREFIX = "olf.klu.";

    private static final boolean AVAILABLE = checkAvailable();

    private static boolean systemPropertiesApplied;

    private Ordering ordering = Ordering.AMD;
    private boolean btf = true;
    private int scale = 2;
    private double tol = 0.001;
    private Check check = Check.RCOND;
    private double rcondThreshold = 1e-12;

    private static native void setDefaults(int ordering, boolean btf, int scale, double tol, int check, double rcondThreshold);

    private static boolean checkAvailable() {
        try {
            KluParameters defaults = new KluParameters();
            setDefaults(defaults.ordering.ordinal(), defaults.btf, defaults.scale, defaults.tol, defaults.check.ordinal(), defaults.rcondThreshold);
            return true;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** True when the native math library takes the parameters. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public Ordering getOrdering() {
        return ordering;
    }

    public KluParameters setOrdering(Ordering ordering) {
        this.ordering = Objects.requireNonNull(ordering);
        return this;
    }

    public boolean isBtf() {
        return btf;
    }

    public KluParameters setBtf(boolean btf) {
        this.btf = btf;
        return this;
    }

    public int getScale() {
        return scale;
    }

    /** -1 none and no check of the values, 0 none, 1 rows divided by their sum, 2 by their maximum. */
    public KluParameters setScale(int scale) {
        if (scale < -1 || scale > 2) {
            throw new IllegalArgumentException("Invalid KLU scaling: " + scale);
        }
        this.scale = scale;
        return this;
    }

    public double getTol() {
        return tol;
    }

    /** The partial pivoting tolerance of a whole factorization. */
    public KluParameters setTol(double tol) {
        this.tol = tol;
        return this;
    }

    public Check getCheck() {
        return check;
    }

    public KluParameters setCheck(Check check) {
        this.check = Objects.requireNonNull(check);
        return this;
    }

    public double getRcondThreshold() {
        return rcondThreshold;
    }

    public KluParameters setRcondThreshold(double rcondThreshold) {
        this.rcondThreshold = rcondThreshold;
        return this;
    }

    /** Makes these the parameters of the KLU decompositions created from now on. */
    public void apply() {
        if (!AVAILABLE) {
            throw new MatrixException("The native math library has no KLU parameters");
        }
        setDefaults(ordering.ordinal(), btf, scale, tol, check.ordinal(), rcondThreshold);
        LOGGER.info("KLU parameters: {}", this);
    }

    /** The parameters of the {@code olf.klu.*} system properties, the defaults for those not set. */
    public static KluParameters fromSystemProperties() {
        KluParameters parameters = new KluParameters();
        String value = System.getProperty(PROPERTY_PREFIX + "ordering");
        if (value != null) {
            parameters.setOrdering(Ordering.valueOf(value.toUpperCase(Locale.ROOT)));
        }
        value = System.getProperty(PROPERTY_PREFIX + "btf");
        if (value != null) {
            parameters.setBtf(Boolean.parseBoolean(value));
        }
        value = System.getProperty(PROPERTY_PREFIX + "scale");
        if (value != null) {
            parameters.setScale(Integer.parseInt(value));
        }
        value = System.getProperty(PROPERTY_PREFIX + "tol");
        if (value != null) {
            parameters.setTol(Double.parseDouble(value));
        }
        value = System.getProperty(PROPERTY_PREFIX + "check");
        if (value != null) {
            parameters.setCheck(Check.valueOf(value.toUpperCase(Locale.ROOT)));
        }
        value = System.getProperty(PROPERTY_PREFIX + "rcondThreshold");
        if (value != null) {
            parameters.setRcondThreshold(Double.parseDouble(value));
        }
        return parameters;
    }

    private static boolean hasSystemProperties() {
        return System.getProperties().stringPropertyNames().stream().anyMatch(name -> name.startsWith(PROPERTY_PREFIX));
    }

    /**
     * Applies the parameters of the system properties, once, when some are set and the native math library takes
     * them. Nothing otherwise.
     */
    public static synchronized void applySystemProperties() {
        if (systemPropertiesApplied) {
            return;
        }
        systemPropertiesApplied = true;
        if (hasSystemProperties()) {
            if (AVAILABLE) {
                fromSystemProperties().apply();
            } else {
                LOGGER.warn("KLU parameters are set by system properties but the native math library has none");
            }
        }
    }

    @Override
    public String toString() {
        return "KluParameters(ordering=" + ordering + ", btf=" + btf + ", scale=" + scale + ", tol=" + tol
                + ", check=" + check + ", rcondThreshold=" + rcondThreshold + ")";
    }
}
