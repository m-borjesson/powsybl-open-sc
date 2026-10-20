/**
 * Copyright (c) 2026
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.sc.cgmes;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.extensions.WindingConnectionType;
import com.powsybl.triplestore.api.PropertyBag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Resolves the connection before parsing attributes that may not apply to it.
 * Neutral impedances are physical ohms on the source end's voltage base.
 */
record TransformerEndConnection(WindingConnectionType type, double rground, double xground) {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransformerEndConnection.class);

    static TransformerEndConnection read(PropertyBag end) {
        String kind = end.getId("connectionKind");
        if ("WindingConnection.Yn".equals(kind)) {
            if (!end.containsKey("grounded")) {
                LOGGER.warn("Transformer '{}' end {} connection {} has no grounded attribute; treating it as ungrounded",
                        end.getId("ID"), end.get("endNumber"), kind);
            }
            return new TransformerEndConnection(end.asBoolean("grounded", false)
                    ? WindingConnectionType.Y_GROUNDED : WindingConnectionType.Y,
                    end.asDouble("rground"), end.asDouble("xground"));
        }
        WindingConnectionType type;
        if ("WindingConnection.Y".equals(kind)) {
            type = WindingConnectionType.Y;
        } else if ("WindingConnection.D".equals(kind)) {
            type = WindingConnectionType.DELTA;
        } else {
            throw new PowsyblException("Leg connection type '" + kind + "' not handled in transformer '"
                    + end.getId("ID") + "' end " + end.get("endNumber"));
        }
        String ignored = List.of("grounded", "rground", "xground").stream()
                .filter(end::containsKey).collect(Collectors.joining(", "));
        if (!ignored.isEmpty()) {
            LOGGER.warn("Transformer '{}' end {} connection {}: ignoring inapplicable grounding attributes [{}]",
                    end.getId("ID"), end.get("endNumber"), kind, ignored);
        }
        return new TransformerEndConnection(type, 0, 0);
    }
}
