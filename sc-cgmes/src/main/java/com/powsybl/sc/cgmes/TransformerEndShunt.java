/**
 * Copyright (c) 2026
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.sc.cgmes;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerFortescue;
import com.powsybl.triplestore.api.PropertyBag;

import java.util.Map;
import java.util.OptionalDouble;

/**
 * Transient import data; ohms remain on the source end's voltage base.
 * An open branch has no finite impedance and must not be encoded as rm=xm=0.
 */
record TransformerEndShunt(State state, OptionalDouble rm, OptionalDouble xm) {
    enum State { MISSING, OPEN, FINITE }

    static TransformerEndShunt read(PropertyBag end) {
        if (!end.containsKey("g0") || !end.containsKey("b0")) {
            return new TransformerEndShunt(State.MISSING, OptionalDouble.empty(), OptionalDouble.empty());
        }
        double g = end.asDouble("g0");
        double b = end.asDouble("b0");
        if (!Double.isFinite(g) || !Double.isFinite(b)) {
            throw invalidShunt(end);
        }
        double scale = Math.max(Math.abs(g), Math.abs(b));
        if (scale == 0) {
            return new TransformerEndShunt(State.OPEN, OptionalDouble.empty(), OptionalDouble.empty());
        }
        // Scale first: g*g+b*b can overflow or underflow even when 1/(g+jb) is representable.
        double scaledG = g / scale;
        double scaledB = b / scale;
        double denominator = scaledG * scaledG + scaledB * scaledB;
        double rm = scaledG / denominator / scale;
        double xm = -scaledB / denominator / scale;
        if (!Double.isFinite(rm) || !Double.isFinite(xm)) {
            throw invalidShunt(end);
        }
        return new TransformerEndShunt(State.FINITE, OptionalDouble.of(rm), OptionalDouble.of(xm));
    }

    private static PowsyblException invalidShunt(PropertyBag end) {
        return new PowsyblException("Transformer '" + end.getId("ID") + "' end " + end.get("endNumber")
                + " has invalid or unrepresentable zero-sequence shunt g0/b0");
    }

    static void applyOpenTwoWindingShunts(TwoWindingsTransformerFortescue extension, Map<Integer, TransformerEndShunt> ends) {
        TransformerEndShunt end1 = ends.get(1);
        TransformerEndShunt end2 = ends.get(2);
        if (end1 != null && end2 != null && end1.state == State.OPEN && end2.state == State.OPEN) {
            extension.setFreeFluxes(true);
        }
    }
}
