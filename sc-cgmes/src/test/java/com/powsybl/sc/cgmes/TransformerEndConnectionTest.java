/**
 * Copyright (c) 2026
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.sc.cgmes;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.extensions.WindingConnectionType;
import com.powsybl.triplestore.api.PropertyBag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransformerEndConnectionTest {

    private static PropertyBag end(String kind) {
        PropertyBag properties = new PropertyBag(List.of("ID", "endNumber", "connectionKind", "grounded", "rground", "xground"), false);
        properties.put("ID", "transformer");
        properties.put("endNumber", "2");
        properties.put("connectionKind", "http://iec.ch/TC57/CIM100#WindingConnection." + kind);
        return properties;
    }

    @Test
    void ynRequiresEffectiveGrounding() {
        PropertyBag properties = end("Yn");
        properties.put("grounded", "true");
        properties.put("rground", "2.5");
        properties.put("xground", "4.0");
        var connection = TransformerEndConnection.read(properties);
        assertEquals(WindingConnectionType.Y_GROUNDED, connection.type());
        assertEquals(2.5, connection.rground());
        assertEquals(4, connection.xground());
        properties.put("grounded", "false");
        assertEquals(WindingConnectionType.Y, TransformerEndConnection.read(properties).type());
    }

    @Test
    void missingGroundedRetainsUngroundedFallback() {
        var connection = TransformerEndConnection.read(end("Yn"));
        assertEquals(WindingConnectionType.Y, connection.type());
        assertTrue(Double.isNaN(connection.rground()));
        assertTrue(Double.isNaN(connection.xground()));
    }

    @Test
    void inapplicableGroundingIsIgnoredWithoutParsingAndWarnedOnce() {
        Logger logger = (Logger) LoggerFactory.getLogger(TransformerEndConnection.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (String kind : List.of("Y", "D")) {
                PropertyBag properties = end(kind);
                properties.put("grounded", "invalid-boolean");
                properties.put("rground", "invalid-resistance");
                properties.put("xground", "invalid-reactance");
                var connection = TransformerEndConnection.read(properties);
                assertEquals(kind.equals("Y") ? WindingConnectionType.Y : WindingConnectionType.DELTA, connection.type());
                assertEquals(0, connection.rground());
                assertEquals(0, connection.xground());
            }
            assertEquals(2, appender.list.size());
            for (ILoggingEvent event : appender.list) {
                assertEquals("WARN", event.getLevel().toString());
                assertTrue(event.getFormattedMessage().contains("transformer"));
                assertTrue(event.getFormattedMessage().contains("end 2"));
                assertTrue(event.getFormattedMessage().contains("grounded, rground, xground"));
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void explicitFalseOnDeltaIsStillAnIgnoredSourceAttribute() {
        PropertyBag properties = end("D");
        properties.put("grounded", "false");
        assertEquals(WindingConnectionType.DELTA, TransformerEndConnection.read(properties).type());
    }

    @Test
    void unsupportedConnectionIncludesEndInDiagnostic() {
        var exception = assertThrows(PowsyblException.class, () -> TransformerEndConnection.read(end("A")));
        assertTrue(exception.getMessage().contains("transformer"));
        assertTrue(exception.getMessage().contains("end 2"));
        assertThrows(PowsyblException.class, () -> TransformerEndConnection.read(end("Z")));
        assertThrows(PowsyblException.class, () -> TransformerEndConnection.read(end("Zn")));
    }
}
