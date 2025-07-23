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
package de.rwth.idsg.steve.myconfig;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static jooq.steve.db.tables.AppLog.APP_LOG;

@Slf4j
@Service
public class LogService {

    @Autowired
    private static DSLContext ctx;

    public LogService(DSLContext ctx){
        this.ctx = ctx;
    }

    public static void saveToDatabase(String chargeBoxId, String sessionId, String msg, String direction) {
        if (chargeBoxId == null || sessionId == null || chargeBoxId.isEmpty() || sessionId.isEmpty()) {
            System.err.println("Invalid chargeBoxId or sessionId");
            return;
        }

        DateTime timestampStr = new DateTime();
        String event = null;
        String payload = null;
        Integer transactionId = null;
        String messageId = null;

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(msg);

            if (root.isArray() && root.size() >= 3) {
                int messageTypeCode = root.get(0).asInt();
                messageId = root.get(1).asText();

                switch (messageTypeCode) {

                    case 2: // CALL
                        event = root.get(2).asText();

                        // ✅ Skip Heartbeat entirely
                        if ("Heartbeat".equalsIgnoreCase(event)) {
                            return;
                        }

                        JsonNode payloadNode = root.get(3);

                        // ✅ Skip MeterValues or StatusNotification if payload is empty
                        if (("MeterValues".equalsIgnoreCase(event) || "StatusNotification".equalsIgnoreCase(event))) {
                            if (payloadNode == null || payloadNode.isNull() ||
                                    (payloadNode.isObject() && !payloadNode.fieldNames().hasNext())) {
                                log.info("Skipping {} event due to empty payload", event);
                                return;
                            }
                        }

                        payload = payloadNode.toString();

                        // Save messageId → event for matching later CALL_RESULT
                        OcppMessageTracker.put(messageId, event);

                        if (payloadNode.has("transactionId")) {
                            transactionId = payloadNode.get("transactionId").asInt();
                        }
                        break;

                    case 3: // CALL_RESULT
                        // Try to fetch original method from tracker
                        int retry = 3;
                        while (retry-- > 0) {
                            event = OcppMessageTracker.get(messageId);
                            if (event != null) break;
                            try {
                                Thread.sleep(30);
                            } catch (InterruptedException ignored) {}
                        }

                        if ("Heartbeat".equalsIgnoreCase(event)) {
                            return;
                        }

                        JsonNode responseNode = root.get(2);

                        // ✅ Skip MeterValues or StatusNotification if response is empty
                        if (("MeterValues".equalsIgnoreCase(event) || "StatusNotification".equalsIgnoreCase(event))) {
                            if (responseNode == null || responseNode.isNull() ||
                                    (responseNode.isObject() && !responseNode.fieldNames().hasNext())) {
                                log.info("Skipping CALL_RESULT of {} due to empty payload", event);
                                return;
                            }
                        }

                        payload = responseNode.toString();
                        OcppMessageTracker.remove(messageId);

                        if (responseNode.has("transactionId")) {
                            transactionId = responseNode.get("transactionId").asInt();
                        }
                        break;

                    case 4: // CALL_ERROR
                        event = "CALL_ERROR";
                        ObjectNode errorObj = mapper.createObjectNode();
                        errorObj.put("errorCode", root.get(2).asText());
                        errorObj.put("errorDescription", root.get(3).asText());
                        if (root.size() > 4) {
                            JsonNode details = root.get(4);
                            if (details.isObject()) {
                                errorObj.set("errorDetails", details);
                            } else {
                                errorObj.put("errorDetails", details.toString());
                            }
                        }
                        payload = errorObj.toString();
                        break;

                    default:
                        event = "UNKNOWN_TYPE_" + messageTypeCode;
                        payload = msg;
                        break;
                }

            } else {
                event = "INVALID_STRUCTURE";
                payload = msg;
            }

        } catch (Exception e) {
            event = "JSON_PARSE_ERROR";
            payload = msg;
            e.printStackTrace();
        }

        try {
            ctx.insertInto(APP_LOG)
                    .set(APP_LOG.TIMESTAMP_STR, timestampStr)
                    .set(APP_LOG.CHARGE_BOX_ID, chargeBoxId)
                    .set(APP_LOG.SESSION_ID, sessionId)
                    .set(APP_LOG.TRANSACTION_ID, transactionId)
                    .set(APP_LOG.EVENT, event)
                    .set(APP_LOG.PAYLOAD, payload)
                    .set(APP_LOG.MESSAGE_ID, messageId)
                    .set(APP_LOG.DIRECTION, direction)
                    .execute();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}