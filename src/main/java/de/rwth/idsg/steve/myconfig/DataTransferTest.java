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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.rwth.idsg.steve.web.controller.DataTransferHandler;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static jooq.steve.db.Tables.VEHICLE_MAC;
@Component
public class DataTransferTest {

    @Autowired
    private DataTransferHandler dataTransferHandler;
    @Autowired
    private DSLContext dsl;

    public void testHandleDataTransfer() {
        // 1. Prepare test data
        String testMac = "VID:AA:BB:CC:DD:EE";
        String jsonPayload = String.format("{\"data\":\"{\\\"mac\\\":\\\"%s\\\"}\"}", testMac);

        try {
            // 2. Parse JSON into JsonNode
            ObjectMapper mapper = new ObjectMapper();
            JsonNode payload = mapper.readTree(jsonPayload);
            String testChargeBoxId = "ChargePoint502";

            // 3. Call the method under test
            dataTransferHandler.handleDataTransfer(payload, testChargeBoxId);

            // 4. Verify the MAC address was inserted in the database
            Integer count = dsl.selectCount()
                    .from(VEHICLE_MAC)
                    .where(VEHICLE_MAC.MAC_ADDRESS.eq(testMac))
                    .fetchOne(0, Integer.class);

            // 5. Print result
            if (count != null && count > 0) {
                System.out.println("Test Passed: MAC stored in database");
            } else {
                System.out.println("Test Failed: MAC not found in database");
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Test Failed: Exception occurred during test");
        }
    }
}
