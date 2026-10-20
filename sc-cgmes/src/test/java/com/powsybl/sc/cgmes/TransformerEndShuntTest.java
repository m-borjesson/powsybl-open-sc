/**
 * Copyright (c) 2026
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.sc.cgmes;

import com.powsybl.commons.PowsyblException;
import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerFortescue;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerFortescueAdder;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerFortescueAdder;
import com.powsybl.triplestore.api.PropertyBag;
import com.powsybl.triplestore.api.PropertyBags;
import com.powsybl.triplestore.api.TripleStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class TransformerEndShuntTest {

    private static PropertyBag end(String g, String b) {
        PropertyBag properties = new PropertyBag(List.of("ID", "endNumber", "g0", "b0"), false);
        properties.put("ID", "transformer");
        properties.put("endNumber", "1");
        properties.putNonNull("g0", g);
        properties.putNonNull("b0", b);
        return properties;
    }

    @Test
    void convertsConductanceAndSusceptanceToSeriesImpedance() {
        var shunt = TransformerEndShunt.read(end("0.003", "-0.004"));
        assertEquals(TransformerEndShunt.State.FINITE, shunt.state());
        assertEquals(120, shunt.rm().orElseThrow(), 1e-12);
        assertEquals(160, shunt.xm().orElseThrow(), 1e-12);
        assertEquals(100, TransformerEndShunt.read(end("0.01", "0")).rm().orElseThrow(), 1e-12);
        assertEquals(100, TransformerEndShunt.read(end("0", "-0.01")).xm().orElseThrow(), 1e-12);
    }

    @Test
    void distinguishesOpenFromMissingAndIncompleteData() {
        var open = TransformerEndShunt.read(end("0", "-0.0"));
        assertEquals(TransformerEndShunt.State.OPEN, open.state());
        assertTrue(open.rm().isEmpty());
        assertTrue(open.xm().isEmpty());
        for (PropertyBag properties : List.of(end(null, null), end("0", null), end(null, "0"))) {
            var missing = TransformerEndShunt.read(properties);
            assertEquals(TransformerEndShunt.State.MISSING, missing.state());
            assertTrue(missing.rm().isEmpty());
            assertTrue(missing.xm().isEmpty());
        }
    }

    @Test
    void reciprocalAvoidsSquaredMagnitudeOverflowAndUnderflow() {
        for (double scale : new double[]{1e-200, 1e200}) {
            var shunt = TransformerEndShunt.read(end(Double.toString(scale), Double.toString(-scale)));
            assertEquals(0.5, shunt.rm().orElseThrow() * scale, 1e-12);
            assertEquals(0.5, shunt.xm().orElseThrow() * scale, 1e-12);
        }
    }

    @Test
    void rejectsNonFiniteOrUnrepresentableSourceData() {
        for (String invalid : List.of("NaN", "Infinity", "bad-value")) {
            var exception = assertThrows(PowsyblException.class, () -> TransformerEndShunt.read(end(invalid, "0")));
            assertTrue(exception.getMessage().contains("transformer"));
            assertTrue(exception.getMessage().contains("end 1"));
        }
        assertThrows(PowsyblException.class, () -> TransformerEndShunt.read(end("4.9e-324", "0")));
    }

    private static TwoWindingsTransformerFortescue extension() {
        Network network = Network.create("shunt-test", "test");
        Substation substation = network.newSubstation().setId("substation").add();
        var vl1 = substation.newVoltageLevel().setId("vl1").setNominalV(100).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        var vl2 = substation.newVoltageLevel().setId("vl2").setNominalV(50).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl1.getBusBreakerView().newBus().setId("bus1").add();
        vl2.getBusBreakerView().newBus().setId("bus2").add();
        var transformer = substation.newTwoWindingsTransformer().setId("transformer")
                .setVoltageLevel1("vl1").setVoltageLevel2("vl2")
                .setBus1("bus1").setConnectableBus1("bus1").setBus2("bus2").setConnectableBus2("bus2")
                .setR(0).setX(1).setG(0).setB(0).setRatedU1(100).setRatedU2(50).add();
        return transformer.newExtension(TwoWindingsTransformerFortescueAdder.class).withFreeFluxes(false).add();
    }

    @Test
    void twoWindingFreeFluxesRequiresBothEndsExplicitlyOpen() {
        var open = TransformerEndShunt.read(end("0", "0"));
        var finite = TransformerEndShunt.read(end("0", "-0.01"));
        var missing = TransformerEndShunt.read(end(null, null));
        var extension = extension();
        for (var ends : List.of(Map.of(1, open), Map.of(1, open, 2, missing),
                Map.of(1, open, 2, finite), Map.of(1, finite, 2, open))) {
            TransformerEndShunt.applyOpenTwoWindingShunts(extension, ends);
            assertFalse(extension.isFreeFluxes());
        }
        TransformerEndShunt.applyOpenTwoWindingShunts(extension, Map.of(2, open, 1, open));
        assertTrue(extension.isFreeFluxes());
    }

    @Test
    void importerAppliesBothZeroEndsIndependentOfQueryOrder() {
        for (boolean reverse : new boolean[]{false, true}) {
            var extension = extension();
            PropertyBag end1 = transformerEnd(1, "0", "0");
            PropertyBag end2 = transformerEnd(2, "0", "0");
            process(extension, reverse ? List.of(end2, end1) : List.of(end1, end2));
            assertTrue(extension.isFreeFluxes());
            assertEquals(com.powsybl.iidm.network.extensions.WindingConnectionType.Y_GROUNDED, extension.getConnectionType1());
        }
    }

    @Test
    void importerDoesNotInferFreeFluxesFromOnlyOneZeroEnd() {
        var extension = extension();
        process(extension, List.of(transformerEnd(1, "0", "0"), transformerEnd(2, "0", "-0.01")));
        assertFalse(extension.isFreeFluxes());
    }

    @Test
    void importerKeepsUngroundedYnAndIgnoresInapplicableYGrounding() {
        for (String kind : List.of("Yn", "Y")) {
            var extension = extension();
            PropertyBag end1 = transformerEnd(1, "0", "0");
            end1.put("connectionKind", "WindingConnection." + kind);
            end1.put("grounded", kind.equals("Yn") ? "false" : "invalid");
            if (kind.equals("Y")) {
                end1.put("rground", "invalid");
                end1.put("xground", "invalid");
            }
            process(extension, List.of(end1, transformerEnd(2, "0", "0")));
            assertEquals(com.powsybl.iidm.network.extensions.WindingConnectionType.Y, extension.getConnectionType1());
            assertEquals(0, extension.getGroundingR1());
            assertEquals(0, extension.getGroundingX1());
        }
    }

    @Test
    void threeWindingOpenFlagIsIndependentForEachLegAndEndOrder() {
        for (int[] order : new int[][]{{1, 2, 3}, {1, 3, 2}, {2, 1, 3}, {2, 3, 1}, {3, 1, 2}, {3, 2, 1}}) {
            Network network = extension().getExtendable().getNetwork();
            Substation substation = network.getSubstation("substation");
            var vl3 = substation.newVoltageLevel().setId("vl3").setNominalV(10).setTopologyKind(TopologyKind.BUS_BREAKER).add();
            vl3.getBusBreakerView().newBus().setId("bus3").add();
            var transformer = substation.newThreeWindingsTransformer().setId("transformer3").setRatedU0(100)
                    .newLeg1().setVoltageLevel("vl1").setBus("bus1").setConnectableBus("bus1")
                    .setR(0).setX(1).setG(0).setB(0).setRatedU(100).add()
                    .newLeg2().setVoltageLevel("vl2").setBus("bus2").setConnectableBus("bus2")
                    .setR(0).setX(1).setG(0).setB(0).setRatedU(50).add()
                    .newLeg3().setVoltageLevel("vl3").setBus("bus3").setConnectableBus("bus3")
                    .setR(0).setX(1).setG(0).setB(0).setRatedU(10).add().add();
            var extension = transformer.newExtension(ThreeWindingsTransformerFortescueAdder.class)
                    .leg1().withFreeFluxes(false).leg2().withFreeFluxes(false).leg3().withFreeFluxes(false).add();
            PropertyBag[] ends = {transformerEnd(1, "0", "0"), transformerEnd(2, "0", "-0.01"), transformerEnd(3, null, null)};
            for (var end : ends) {
                end.put("ID", "transformer3");
            }
            process(network, List.of(ends[order[0] - 1], ends[order[1] - 1], ends[order[2] - 1]));
            assertTrue(extension.getLeg1().isFreeFluxes());
            assertFalse(extension.getLeg2().isFreeFluxes());
            assertFalse(extension.getLeg3().isFreeFluxes());
        }
    }

    private static PropertyBag transformerEnd(int number, String g, String b) {
        PropertyBag properties = end(g, b);
        properties.put("endNumber", Integer.toString(number));
        properties.put("r0", "1");
        properties.put("x0", "1");
        properties.put("ratedS", "10");
        properties.put("connectionKind", "WindingConnection.Yn");
        properties.put("grounded", "true");
        properties.put("rground", "4");
        properties.put("xground", "0");
        return properties;
    }

    private static void process(TwoWindingsTransformerFortescue extension, List<PropertyBag> ends) {
        process(extension.getExtendable().getNetwork(), ends);
    }

    private static void process(Network network, List<PropertyBag> ends) {
        TripleStore tripleStore = (TripleStore) Proxy.newProxyInstance(TripleStore.class.getClassLoader(),
                new Class<?>[]{TripleStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("query")) {
                        return new PropertyBags(args[0].toString().contains("PowerTransformerEnd.r0") ? ends : List.of());
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        CgmesModel model = (CgmesModel) Proxy.newProxyInstance(CgmesModel.class.getClassLoader(),
                new Class<?>[]{CgmesModel.class}, (proxy, method, args) -> {
                    if (method.getName().equals("tripleStore")) {
                        return tripleStore;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        new CgmesShortCircuitImportPostProcessor().process(network, model);
    }
}
