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

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static jooq.steve.db.Tables.TRANSACTION_METER_VALUES;

@Service
public class TransactionMeterValuesService {
    @Autowired
    private DSLContext dslContext;

    public void insertTransactionMeterValues(TransactionMeterValues transactionMeterValues) {
      int id =transactionMeterValues.getTransactionPk();
        System.out.println(id);

        try {

            dslContext.insertInto(TRANSACTION_METER_VALUES)
                    .set(TRANSACTION_METER_VALUES.OCPP_TAG_ID, transactionMeterValues.getOcppTagPk())
                    .set(TRANSACTION_METER_VALUES.CHARGE_BOX_ID, transactionMeterValues.getChargeBoxId())
                    .set(TRANSACTION_METER_VALUES.TRANSACTION_PK, transactionMeterValues.getTransactionPk())
                    .set(TRANSACTION_METER_VALUES.CONNECTOR_PK, transactionMeterValues.getConnectorPk())
                    .set(TRANSACTION_METER_VALUES.VOLTAGE, transactionMeterValues.getVoltage())
                    .set(TRANSACTION_METER_VALUES.CURRENT, transactionMeterValues.getCurrent())
                    .set(TRANSACTION_METER_VALUES.POWER, transactionMeterValues.getPower())
                    .set(TRANSACTION_METER_VALUES.ENERGY, transactionMeterValues.getEnergy())
                    .set(TRANSACTION_METER_VALUES.SOC, transactionMeterValues.getSoc())
                    .execute();

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

}
