/**
 * Copyright (c) 2026
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.sc.util.extensions;

import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.PiModel;
import com.powsybl.openloadflow.network.SimplePiModel;
import com.powsybl.sc.util.AdmittanceEquationSystem;
import org.apache.commons.math3.complex.Complex;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static com.powsybl.iidm.network.extensions.WindingConnectionType.Y_GROUNDED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HomopolarModelTest {

    private static LfBranch branch(PiModel piModel, ScTransfo2W transformer) {
        return (LfBranch) Proxy.newProxyInstance(LfBranch.class.getClassLoader(), new Class<?>[]{LfBranch.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPiModel" -> piModel;
                    case "getBranchType" -> LfBranch.BranchType.TRANSFO_2;
                    case "getId" -> "transformer";
                    case "getProperty" -> ShortCircuitExtensions.PROPERTY_SHORT_CIRCUIT.equals(args[0]) ? transformer : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void finiteMagnetizingBranchUsesImpedanceMatrixInverse() {
        // Independent T circuit: Za=Zb=0.5, Zm=1. Its terminal impedance is
        // [[1.5, 1], [1, 1.5]], whose inverse is [[1.2, -0.8], [-0.8, 1.2]].
        PiModel piModel = new SimplePiModel().setR(1).setG1(1).setR1(1);
        ScTransfo2W transformer = new ScTransfo2W(Y_GROUNDED, Y_GROUNDED,
                Complex.ONE, false, Complex.ZERO, Complex.ZERO);
        var matrix = HomopolarModel.build(branch(piModel, transformer), AdmittanceEquationSystem.FrequencyType.FREQ_50_HZ)
                .getHomopolarAdmittanceMatrix();
        assertEquals(1.2, matrix.get(0, 0).getReal(), 1e-12);
        assertEquals(-0.8, matrix.get(0, 1).getReal(), 1e-12);
        assertEquals(-0.8, matrix.get(1, 0).getReal(), 1e-12);
        assertEquals(1.2, matrix.get(1, 1).getReal(), 1e-12);
        assertEquals(0, matrix.get(0, 0).getImaginary(), 1e-12);
    }
}
