/*
 * SteVe - SteckdosenVerwaltung - https://github.com/steve-community/steve
 * Copyright (C) ${license.git.copyrightYears} SteVe Community Team
 * All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package de.rwth.idsg.steve.ocpp.ws;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.rwth.idsg.steve.myconfig.AppContextProvider;
import de.rwth.idsg.steve.myconfig.LogService;
import de.rwth.idsg.steve.myconfig.OcppMessageTracker;
import lombok.extern.slf4j.Slf4j;

import org.jooq.DSLContext;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * @author Sevket Goekay <sevketgokay@gmail.com>
 * @since 10.05.2018
 */
@Slf4j
public final class WebSocketLogger {

    private WebSocketLogger() { }

    public static void connected(String chargeBoxId, WebSocketSession session) {
        log.info("[chargeBoxId={}, sessionId={}] Connection is established", chargeBoxId, session.getId());
    }

    public static void closed(String chargeBoxId, WebSocketSession session, CloseStatus closeStatus) {
        log.warn("[chargeBoxId={}, sessionId={}] Connection is closed, status: {}", chargeBoxId, session.getId(), closeStatus);
    }

    public static void sending(String chargeBoxId, WebSocketSession session, String msg) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(msg);

            if (root.isArray() && root.size() >= 3) {
                int messageType = root.get(0).asInt();
                if (messageType == 2) { // CALL
                    String messageId = root.get(1).asText();
                    String event = root.get(2).asText(); // e.g., BootNotification, RemoteStartTransaction
                    OcppMessageTracker.put(messageId, event); // ✅ Save for tracking
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse message for tracking", e);
        }

        log.info("[chargeBoxId={}, sessionId={}] Sending: {}", chargeBoxId, session.getId(), msg);
        String sessionId = session.getId();
        String direction = "Sending" + chargeBoxId;
        LogService.saveToDatabase(chargeBoxId, sessionId, msg, direction);
    }

    public static void sendingPing(String chargeBoxId, WebSocketSession session) {
        log.debug("[chargeBoxId={}, sessionId={}] Sending ping message", chargeBoxId, session.getId());
    }

    public static void receivedPong(String chargeBoxId, WebSocketSession session) {
        log.debug("[chargeBoxId={}, sessionId={}] Received pong message", chargeBoxId, session.getId());
    }

    public static void receivedText(String chargeBoxId, WebSocketSession session, String msg) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(msg);

            if (root.isArray() && root.size() >= 3) {
                int messageType = root.get(0).asInt();
                if (messageType == 2) { // CALL from charge point
                    String messageId = root.get(1).asText();
                    String event = root.get(2).asText();
                    OcppMessageTracker.put(messageId, event); // ✅ Track the incoming CALL
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse received message for tracking", e);
        }
        log.info("[chargeBoxId={}, sessionId={}] Received: {}", chargeBoxId, session.getId(), msg);
        String sessionId = session.getId();
        String direction = "Received by Server from " + chargeBoxId;
        LogService.saveToDatabase(chargeBoxId, sessionId, msg, direction);
    }

    public static void receivedEmptyText(String chargeBoxId, WebSocketSession session) {
        log.warn("[chargeBoxId={}, sessionId={}] Received empty text message. Will pretend this never happened.", chargeBoxId, session.getId());
    }

    public static void pingError(String chargeBoxId, WebSocketSession session, Throwable t) {
        if (log.isErrorEnabled()) {
            log.error("[chargeBoxId=" + chargeBoxId + ", sessionId=" + session.getId() + "] Ping error", t);
        }
    }

    public static void transportError(String chargeBoxId, WebSocketSession session, Throwable t) {
        if (log.isErrorEnabled()) {
            log.error("[chargeBoxId=" + chargeBoxId + ", sessionId=" + session.getId() + "] Transport error", t);
        }
    }
}
