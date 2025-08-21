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
package de.rwth.idsg.steve.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import static jooq.steve.db.Tables.*;

@Slf4j
@Component
public class DataTransferHandler {

    @Autowired
    private DSLContext dsl;

    public void handleDataTransfer(JsonNode payload, String chargeBoxId) {

        try {
            if (payload == null || !payload.has("data")) {
                log.warn("Missing 'data' in DataTransfer payload");
                return;
            }

            String dataJson = payload.get("data").asText();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode dataNode = mapper.readTree(dataJson);

            if (dataNode.has("mac")) {
                String mac = dataNode.get("mac").asText();// This is your VID:xxxx
                log.info("Received MAC (VID) from DataTransfer: {}", mac);

                Integer count = dsl.selectCount()
                        .from(VEHICLE_MAC)
                        .where(VEHICLE_MAC.MAC_ADDRESS.eq(mac))
                        .fetchOne(0, Integer.class); // box into Integer

                if (count == null || count == 0) {
                    dsl.insertInto(VEHICLE_MAC)
                            .set(VEHICLE_MAC.MAC_ADDRESS, mac)
                            .set(VEHICLE_MAC.USER_ID, 1L)
                            .execute();

                    log.info("Inserted MAC '{}' into vehicle_mac table", mac);
                } else {
                    log.info("MAC '{}' already exists in vehicle_mac table", mac);
                }
            }

        } catch (Exception e) {
            log.error("Error processing DataTransfer for MAC association", e);
        }
    }

    public void sendToPhp(String idTag, String dcIdTag) {

        try {
            RestTemplate restTemplate = new RestTemplate();

            //Must use MultiValueMap for form data
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("idtag", idTag);
            formData.add("dc_idtag", dcIdTag);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED); // Key point

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(formData, headers);
            String url = "http://15.207.37.132/auto_charge/idtag.php";

            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            log.info("Sent idTag+VID to PHP. Status: {}, Body: {}", response.getStatusCode(), response.getBody());

        } catch (Exception e) {
            log.error("Error sending idTag and VID to PHP", e);
        }
    }
}
