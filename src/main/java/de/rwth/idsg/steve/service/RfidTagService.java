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
package de.rwth.idsg.steve.service;

import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static jooq.steve.db.Tables.RFID_TAG;
import static jooq.steve.db.Tables.USER_ACCOUNT;
@Slf4j
@Service
public class RfidTagService {

    @Autowired
    private DSLContext dsl;

    public void storeRfidTag(String rfidIdTag, String userIdTag) {
        // Step 1: Ensure userIdTag exists in user_account table as primary_id_tag
        Long userId = dsl.select(USER_ACCOUNT.ID)
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.PRIMARY_ID_TAG.eq(userIdTag))
                .fetchOneInto(Long.class);

        if (userId == null) {
            dsl.insertInto(USER_ACCOUNT)
                    .set(USER_ACCOUNT.PRIMARY_ID_TAG, userIdTag)
                    .execute();

            log.info("Created user_account with primary_id_tag '{}'", userIdTag);

            // Re-fetch userId after insert
            userId = dsl.select(USER_ACCOUNT.ID)
                    .from(USER_ACCOUNT)
                    .where(USER_ACCOUNT.PRIMARY_ID_TAG.eq(userIdTag))
                    .fetchOneInto(Long.class);
        }

        // Step 2: Check if RFID idTag already exists in rfid_tag
        boolean rfidExists = dsl.fetchExists(
                dsl.selectOne()
                        .from(RFID_TAG)
                        .where(RFID_TAG.ID_TAG.eq(rfidIdTag))
        );

        if (!rfidExists) {
            // Step 3: Insert RFID tag into rfid_tag table
            dsl.insertInto(RFID_TAG)
                    .set(RFID_TAG.ID_TAG, rfidIdTag)
                    .set(RFID_TAG.USER_ID, userId)
                    .execute();

            log.info("Stored new RFID idTag '{}' linked to userId {}", rfidIdTag, userId);
        } else {
            log.info("RFID idTag '{}' already exists in rfid_tag table", rfidIdTag);
        }
    }
}
