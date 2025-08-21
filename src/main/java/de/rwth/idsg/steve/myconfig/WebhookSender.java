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
package de.rwth.idsg.steve.myconfig;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

public class WebhookSender {

    private static final RestTemplate restTemplate = new RestTemplate();
    private static final String WEBHOOK_URL = "http://localhost:8080/api/webhook/event";

    public static void send(WebhookPayload payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Object> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(WEBHOOK_URL, entity, String.class);

            System.out.println("✅ Webhook sent for event: " + payload.getEvent() + " → " + response.getStatusCode());
        } catch (Exception e) {
            System.err.println("❌ Webhook sending failed: " + e.getMessage());
        }
    }

//    public static void sendSimple(String chargeBoxId, String event, String timestamp) {
//        WebhookPayload payload = new WebhookPayload();
//        payload.setChargeBoxId(chargeBoxId);
//        payload.setEvent(event);
//        payload.setTimestamp(timestamp);
//        send(payload);
//    }

}
