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

import de.rwth.idsg.steve.ocpp.ChargePointService16_InvokerImpl;
import de.rwth.idsg.steve.ocpp.OcppTransport;
import de.rwth.idsg.steve.ocpp.OcppVersion;
import de.rwth.idsg.steve.ocpp.task.RemoteStopTransactionTask;
import de.rwth.idsg.steve.repository.TaskStore;
import de.rwth.idsg.steve.repository.dto.ChargePointSelect;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStopTransactionParams;
import ocpp.cs._2015._10.MeterValue;
import ocpp.cs._2015._10.SampledValue;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static jooq.steve.db.Tables.*;
import static jooq.steve.db.tables.Connector.CONNECTOR;
import static jooq.steve.db.tables.TransactionStart.TRANSACTION_START;


@Service
public class WalletMonitorService {
    @Autowired
    private DSLContext ctx;
    @Autowired
    private TransactionMeterValuesService transactionMeterValuesService;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ChargePointService16_InvokerImpl cpsImpl;
    @Autowired
    RestTemplate restTemplate;

    private final Map<Integer, Double> lastEnergyMap = new ConcurrentHashMap<>();

    public void checkAndStopIfLowBalance(List<MeterValue> list, Integer transactionId, int connectorPk) {

        String chargeBoxId = ctx.select(CONNECTOR.CHARGE_BOX_ID)
                .from(CONNECTOR)
                .where(CONNECTOR.CONNECTOR_PK.eq(connectorPk))
                .fetchOne(CONNECTOR.CHARGE_BOX_ID);

        String idTag = ctx.select(TRANSACTION_START.ID_TAG)
                .from(TRANSACTION_START)
                .where(TRANSACTION_START.TRANSACTION_PK.eq(transactionId))
                .fetchOne(TRANSACTION_START.ID_TAG);

        String apiUrl = "http://localhost:8080/api/wallet";
//        Map<String, String> uriVars = new HashMap<>();
//        uriVars.put("idTag", idTag);
        WalletAndTariff response = restTemplate.getForEntity(apiUrl, WalletAndTariff.class).getBody();
        double walletAmount = response.getWalletAmount();
        double tariffRate = response.getTariffRate();

        lastEnergyMap.computeIfAbsent(transactionId, id -> {
            // Step 1: Get the last transaction PK for this connector (before current one)
            Integer previousTransactionPk = ctx.select(TRANSACTION_METER_VALUES.TRANSACTION_PK)
                    .from(TRANSACTION_METER_VALUES)
                    .where(TRANSACTION_METER_VALUES.CHARGE_BOX_ID.eq(chargeBoxId)
                            .and(TRANSACTION_METER_VALUES.CONNECTOR_PK.eq(connectorPk))
                            .and(TRANSACTION_METER_VALUES.TRANSACTION_PK.lt(transactionId))) // previous tx only
                    .orderBy(TRANSACTION_METER_VALUES.EVENT_TIMESTAMP.desc()) // get latest event from previous tx
                    .limit(1)
                    .fetchOne(TRANSACTION_METER_VALUES.TRANSACTION_PK);

            if (previousTransactionPk == null) {
                System.out.println("⚠️ No previous transaction found — setting lastEnergy = 0.0");
                return 0.0;
            }

            // Step 2: From that previous transaction, get its LAST energy value
            Double dbEnergy = ctx.select(TRANSACTION_METER_VALUES.ENERGY)
                    .from(TRANSACTION_METER_VALUES)
                    .where(
                            TRANSACTION_METER_VALUES.CHARGE_BOX_ID.eq(chargeBoxId)
                                    .and(TRANSACTION_METER_VALUES.CONNECTOR_PK.eq(connectorPk))
                                    .and(TRANSACTION_METER_VALUES.TRANSACTION_PK.eq(previousTransactionPk))
                    )
                    .orderBy(TRANSACTION_METER_VALUES.EVENT_TIMESTAMP.desc()) // latest energy value
                    .limit(1)
                    .fetchOne(TRANSACTION_METER_VALUES.ENERGY);

            return dbEnergy != null ? dbEnergy : 0.0;
        });

        double lastEnergy = lastEnergyMap.get(transactionId);

        TransactionMeterValues transactionMeterValues = new TransactionMeterValues();
        transactionMeterValues.setChargeBoxId(chargeBoxId);
        transactionMeterValues.setConnectorPk(connectorPk);
        transactionMeterValues.setTransactionPk(transactionId);
        transactionMeterValues.setOcppTagPk(idTag);

        for (MeterValue meterValue : list) {
            for (SampledValue sampledValue : meterValue.getSampledValue()) { // fixed typo here
                String measurand = sampledValue.isSetMeasurand() ? sampledValue.getMeasurand().value() : null;
                String valueStr = sampledValue.getValue();

                if (measurand != null && valueStr != null) {
                    try {
                        double value = Double.parseDouble(valueStr);

                        switch (measurand) {
                            case "Voltage":
                                transactionMeterValues.setVoltage(value);
                                break;
                            case "Power.Active.Import":
                                transactionMeterValues.setPower(value);
                                break;
                            case "Energy.Active.Import.Register":
                                transactionMeterValues.setEnergy(value);
                                break;
                            case "SoC":
                                transactionMeterValues.setSoc(value);
                                break;
                            case "Power.Offered":
                                transactionMeterValues.setCurrent(value);
                                break;
                        }

                    } catch (NumberFormatException e) {
                        System.err.println("Invalid numeric value: " + valueStr);
                    }
                }
            }

            transactionMeterValuesService.insertTransactionMeterValues(transactionMeterValues);

            double currentEnergy = transactionMeterValues.getEnergy();

//            double walletAmount = 20; // ₹ wallet balance

            double totalAmount = calculateAndUpdateGst(lastEnergy, currentEnergy, transactionId, tariffRate);

            if (totalAmount > walletAmount) {
                System.out.println("⚠️ Unitfare exceeded the wallet amount, triggering RemoteStopTransaction");

                ChargePointSelect cps = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);

                RemoteStopTransactionParams params = new RemoteStopTransactionParams();
                params.setTransactionId(transactionId);
                params.setChargePointSelectList(Collections.singletonList(cps));

                RemoteStopTransactionTask task = new RemoteStopTransactionTask(OcppVersion.V_16, params) {
                    @Override
                    public ocpp.cp._2015._10.RemoteStopTransactionRequest getOcpp16Request() {
                        ocpp.cp._2015._10.RemoteStopTransactionRequest req = new ocpp.cp._2015._10.RemoteStopTransactionRequest();
                        req.setTransactionId(params.getTransactionId());
                        return req;
                    }
                };

                cpsImpl.remoteStopTransaction(cps, task);
                taskStore.add(task);

            } else {
                System.out.println("Continue to charging");
            }
        }
    }

    private double calculateAndUpdateGst(double lastEnergy, double currentEnergy, int transactionId, double tariffRate) {

//        double ratePerKWh = 3000.0; // ₹ per kWh
        double gstRate = 18.0;    // %

        double rawEnergy = currentEnergy - lastEnergy;
        System.out.println("LastEnergy: " + lastEnergy);
        System.out.println("CurrentEnergy: " + currentEnergy);
        double energy = rawEnergy < 0 ? 0 : rawEnergy;
        double energyKWh = energy / 1000.0;
        double energyCost = energyKWh * tariffRate;
        double gstAmount = energyCost * gstRate / 100.0;
        double totalAmount = energyCost + gstAmount;

        System.out.println("Energy Used (kWh): " + energyKWh);
        System.out.println("Energy Cost: ₹" + energyCost);
        System.out.println("GST: ₹" + gstAmount);
        System.out.println("Total with GST: ₹" + totalAmount);

        ctx.update(TRANSACTION_METER_VALUES)
                .set(TRANSACTION_METER_VALUES.GST_AMOUNT, gstAmount)
                .set(TRANSACTION_METER_VALUES.TOTAL_AMOUNT, totalAmount)
                .where(TRANSACTION_METER_VALUES.TRANSACTION_PK.eq(transactionId))
                .orderBy(TRANSACTION_METER_VALUES.EVENT_TIMESTAMP.desc())
                .limit(1)
                .execute();

        return totalAmount;
    }

    public void triggerRemoteStopTransaction(String chargeBoxId, int transactionId) {
        ChargePointSelect cps = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);

        RemoteStopTransactionParams params = new RemoteStopTransactionParams();
        params.setTransactionId(transactionId);
        params.setChargePointSelectList(Collections.singletonList(cps));

        RemoteStopTransactionTask task = new RemoteStopTransactionTask(OcppVersion.V_16, params) {
            @Override
            public ocpp.cp._2015._10.RemoteStopTransactionRequest getOcpp16Request() {
                ocpp.cp._2015._10.RemoteStopTransactionRequest req = new ocpp.cp._2015._10.RemoteStopTransactionRequest();
                req.setTransactionId(params.getTransactionId());
                return req;
            }
        };

        cpsImpl.remoteStopTransaction(cps, task);
        taskStore.add(task);

    }

}



