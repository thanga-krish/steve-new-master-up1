/*
 * SteVe - SteckdosenVerwaltung - https://github.com/steve-community/steve
 * Copyright (C) 2013-2025 SteVe Community Team
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

import de.rwth.idsg.ocpp.jaxb.RequestType;
import de.rwth.idsg.steve.SteveException;
import de.rwth.idsg.steve.ocpp.CommunicationTask;
import de.rwth.idsg.steve.ocpp.ws.data.ActionResponsePair;
import de.rwth.idsg.steve.ocpp.ws.data.CommunicationContext;
import de.rwth.idsg.steve.ocpp.ws.data.FutureResponseContext;
import de.rwth.idsg.steve.ocpp.ws.data.OcppJsonCall;
import de.rwth.idsg.steve.ocpp.ws.ocpp16.Ocpp16TypeStore;
import de.rwth.idsg.steve.ocpp.ws.pipeline.OutgoingCallPipeline;
import de.rwth.idsg.steve.repository.dto.ChargePointSelect;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.server.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


/**
 * @author Sevket Goekay <sevketgokay@gmail.com>
 * @since 20.03.2015
 */
@Slf4j
@RequiredArgsConstructor
public class ChargePointServiceInvoker {

    private final OutgoingCallPipeline outgoingCallPipeline;
    private final AbstractWebSocketEndpoint endpoint;
    private final TypeStore typeStore;

    private final Map<String, WebSocketSession> session = new ConcurrentHashMap<>();

    /**
     * Just a wrapper to make try-catch block and exception handling stand out
     */
    public void  runPipeline(ChargePointSelect cps, CommunicationTask task) {

        String chargeBoxId = cps.getChargeBoxId();
        try {
            run(chargeBoxId, task);
        } catch (Exception e) {
            log.error("Exception occurred", e);
            // Outgoing call failed due to technical problems. Pass the exception to handler to inform the user
            task.defaultCallback().failed(chargeBoxId, e);
        }
    }

    /**
     * Actual processing
     */
    private void run(String chargeBoxId, CommunicationTask task) {

        RequestType request = task.getRequest();

        ActionResponsePair pair = typeStore.findActionResponse(request);
        ActionResponsePair pairNew = Ocpp16TypeStore.INSTANCE.findActionResponse(request);
        if (pairNew == null && pair == null) {
            throw new SteveException("Action name is not found");
        }

        if (pairNew != null && pair == null) {
            pair = pairNew;
        }

        // Get or refresh session
        WebSocketSession wsSession = session.computeIfAbsent(chargeBoxId, endpoint::getSession);

        // ✅ Check if session is null or closed
        if (wsSession == null || !wsSession.isOpen()) {
            log.error("WebSocket session for {} is null or closed. Cannot send OCPP message.", chargeBoxId);
            throw new SteveException("Cannot communicate with " + chargeBoxId + ": WebSocket is closed");
        }

        // Debug session state (optional)
        session.forEach((key, value) -> {
            System.out.println("ChargeBoxId: " + key + " | Session open: " + (value != null && value.isOpen()));
        });

        // Prepare OCPP call
        OcppJsonCall call = new OcppJsonCall();
        call.setMessageId(UUID.randomUUID().toString());
        call.setPayload(request);
        call.setAction(pair.getAction());

        FutureResponseContext frc = new FutureResponseContext(task, pair.getResponseClass());

        CommunicationContext context = new CommunicationContext(wsSession, chargeBoxId);
        context.setOutgoingMessage(call);
        context.setFutureResponseContext(frc);

        // Send the OCPP message
        outgoingCallPipeline.accept(context);
    }
}
