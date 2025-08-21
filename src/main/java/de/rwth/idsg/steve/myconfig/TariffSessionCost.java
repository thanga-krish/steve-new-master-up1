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

import de.rwth.idsg.steve.ocpp.ChargePointService16_InvokerImpl;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.jooq.*;
import static org.jooq.impl.DSL.max;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import java.util.*;

import static jooq.steve.db.Tables.*;


@Service
public class TariffSessionCost {

    @Autowired
    private DSLContext ctx;

    @Autowired
    private ChargePointService16_InvokerImpl cpsImpl;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ChargingSessionManager chargingSessionManager;
    @Autowired
    private CustomStopReasonStore customStopReasonStore;

    public void callPhpAndProcess(String chargeBoxId, String idtag, Integer connectorId, Integer transactionId) {

        BigDecimal walletAmount = fetchWalletAmountFromPhp(idtag);
        System.out.println("Wallet Amount from PHP = " + walletAmount);
        List<Tariff> tariffs = fetchTariffListFromPhp(chargeBoxId);

        if (walletAmount == null || tariffs == null || tariffs.isEmpty()) {
            System.out.println("Failed to fetch wallet or tariffs. Aborting.");
            return;
        }

        // Continue with billing logic
        tariffAndGstSplitBilling(idtag, walletAmount, connectorId, transactionId);
    }

    public BigDecimal fetchWalletAmountFromPhp(String idtag) {
        try {
            //Send idtag as query param
            //String encodedIdTag = URLEncoder.encode(idtag, StandardCharsets.UTF_8);
            String url = "http://15.207.37.132/auto_charge/auto.php?idtag=" + idtag;

            // No headers/body needed
            HttpEntity<Void> requestEntity = new HttpEntity<>(null);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,  // PHP expects POST
                    requestEntity,
                    Map.class
            );
            System.out.println("Response: " + response);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object amount = response.getBody().get("wallet_amount");
                return new BigDecimal(amount.toString());
            }
        } catch (Exception e) {
            System.out.println("Error fetching wallet amount: " + e.getMessage());
        }
        return null;
    }
    public List<Tariff> fetchTariffListFromPhp(String chargeBoxId) {
        try {
            String tariffApiUrl = "http://15.207.37.132/test/tod.php?charger_id=" + chargeBoxId;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = new HashMap<>();
            body.put("chargeBoxId", chargeBoxId);

            HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<PhpTariffResponse> response = restTemplate.exchange(
                    tariffApiUrl,
                    HttpMethod.GET,
                    entity,
                    PhpTariffResponse.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody().getTariffs();
            }
        } catch (Exception e) {
            System.out.println("Error fetching tariff list: " + e.getMessage());
        }
        return null;
    }

    public void tariffAndGstSplitBilling(String idtag, BigDecimal walletAmount,Integer connectorId, Integer transactionId) {
        System.out.println("Entered tariffAndGstSplitBilling()");

        // Get all active sessions for the idTag
        List<Record4<Integer, Integer, String, Integer>> activeTransactions = ctx.select(
                        TRANSACTION_START.CONNECTOR_PK,
                        TRANSACTION_START.TRANSACTION_PK,
                        CONNECTOR.CHARGE_BOX_ID,
                        CONNECTOR.CONNECTOR_ID)
                .from(TRANSACTION_START)
                .join(CONNECTOR).on(TRANSACTION_START.CONNECTOR_PK.eq(CONNECTOR.CONNECTOR_PK))
                .where(TRANSACTION_START.ID_TAG.eq(idtag))
                .andNotExists(ctx.selectOne()
                        .from(TRANSACTION_STOP)
                        .where(TRANSACTION_STOP.TRANSACTION_PK.eq(TRANSACTION_START.TRANSACTION_PK)))
                .fetch();

        if (activeTransactions.isEmpty()) {
            System.out.println("No active sessions for idTag = " + idtag);
            return;
        }

        Map<String, BigDecimal> deviceCostMap = new LinkedHashMap<>();
        BigDecimal totalUserCost = BigDecimal.ZERO;

        for (Record4<Integer, Integer, String, Integer> txn : activeTransactions) {
            Integer connectorPk = txn.value1();
            String chargeBoxId = txn.value3();

            // Fetch tariff list for this chargeBoxId
            List<Tariff> tariffList = fetchTariffListFromPhp(chargeBoxId);
            if (tariffList == null || tariffList.isEmpty()) {
                System.out.println("No tariff list for " + chargeBoxId);
                continue;
            }

            // Calculate incremental cost for this connector/session
            BigDecimal sessionCost = calculatePartialCost(connectorPk,transactionId, tariffList, chargeBoxId);

            // Group cost per device and sum total
            deviceCostMap.merge(chargeBoxId, sessionCost, BigDecimal::add);
            totalUserCost = totalUserCost.add(sessionCost);
        }

        // Print per-device cost
        System.out.println("\n========== Shared Wallet Billing ==========");
        for (Map.Entry<String, BigDecimal> entry : deviceCostMap.entrySet()) {
            System.out.printf("Device: %s | Session Cost: ₹%.2f\n", entry.getKey(), entry.getValue());
        }

        // Check combined wallet
        BigDecimal remaining = walletAmount.subtract(totalUserCost);
        System.out.printf("→ Combined Cost: ₹%.2f | Wallet: ₹%.2f | Remaining: ₹%.2f\n",
                totalUserCost, walletAmount, remaining);

        if (remaining.compareTo(BigDecimal.valueOf(30)) <= 0) {
            System.out.println("Wallet too low. Triggering RemoteStopTransaction for all active sessions...");
            for (Record4<Integer, Integer, String, Integer> txn : activeTransactions) {
                Integer connectorPk = txn.value1();
                Integer txnId = txn.value2();
                String boxId = txn.value3();

                chargingSessionManager.stopSession(boxId, connectorPk, txnId, idtag);
                customStopReasonStore.putReason(txnId, "StopByServer");
            }
        } else {
            System.out.println("Wallet sufficient. Continue charging.");
        }
    }

    public BigDecimal calculatePartialCost(Integer connectorPk, Integer transactionId, List<Tariff> tariffList, String boxId) {
        DateTime txnStartTime = ctx.select(TRANSACTION_START.EVENT_TIMESTAMP)
                .from(TRANSACTION_START)
                .where(TRANSACTION_START.TRANSACTION_PK.eq(transactionId))
                .fetchOneInto(DateTime.class);

        // Get last billed timestamp
        DateTime lastBilledTime = ctx.select(max(TRANSACTION_METER_VALUES.EVENT_TIMESTAMP))
                .from(TRANSACTION_METER_VALUES)
                .where(TRANSACTION_METER_VALUES.TRANSACTION_PK.eq(transactionId))
                .fetchOneInto(DateTime.class);

        DateTime fromTime = (lastBilledTime != null) ? lastBilledTime : txnStartTime;
        DateTime now = DateTime.now();

        // Get previous meter value just before fromTime
        Record2<DateTime, BigDecimal> prevMeter = ctx.select(
                        CONNECTOR_METER_VALUE.VALUE_TIMESTAMP,
                        CONNECTOR_METER_VALUE.VALUE.cast(BigDecimal.class))
                .from(CONNECTOR_METER_VALUE)
                .where(CONNECTOR_METER_VALUE.CONNECTOR_PK.eq(connectorPk)
                        .and(CONNECTOR_METER_VALUE.MEASURAND.eq("Energy.Active.Import.Register"))
                        .and(CONNECTOR_METER_VALUE.VALUE_TIMESTAMP.lessOrEqual(fromTime)))
                .orderBy(CONNECTOR_METER_VALUE.VALUE_TIMESTAMP.desc())
                .limit(1)
                .fetchOne();

        if (prevMeter == null) {
            System.out.println("No previous meter value for connectorPk: " + connectorPk);
            return BigDecimal.ZERO;
        }

        // Get new meter values after fromTime
        List<Record2<DateTime, BigDecimal>> meterValues = ctx.select(
                        CONNECTOR_METER_VALUE.VALUE_TIMESTAMP,
                        CONNECTOR_METER_VALUE.VALUE.cast(BigDecimal.class))
                .from(CONNECTOR_METER_VALUE)
                .where(CONNECTOR_METER_VALUE.CONNECTOR_PK.eq(connectorPk)
                        .and(CONNECTOR_METER_VALUE.MEASURAND.eq("Energy.Active.Import.Register"))
                        .and(CONNECTOR_METER_VALUE.VALUE_TIMESTAMP.greaterThan(fromTime))
                        .and(CONNECTOR_METER_VALUE.VALUE_TIMESTAMP.lessOrEqual(now)))
                .orderBy(CONNECTOR_METER_VALUE.VALUE_TIMESTAMP.asc())
                .fetch();

        if (meterValues.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // Prepend previous meter to list
        List<Record2<DateTime, BigDecimal>> allMeters = new ArrayList<>();
        allMeters.add(prevMeter);
        allMeters.addAll(meterValues);

        BigDecimal sessionCost = BigDecimal.ZERO;

        for (int i = 1; i < allMeters.size(); i++) {
            DateTime prevTime = allMeters.get(i - 1).value1().withZone(DateTimeZone.forOffsetHoursMinutes(5, 30));
            DateTime currTime = allMeters.get(i).value1().withZone(DateTimeZone.forOffsetHoursMinutes(5, 30));
            BigDecimal prevValue = allMeters.get(i - 1).value2();
            BigDecimal currValue = allMeters.get(i).value2();

            double energyDeltaWh = currValue.subtract(prevValue).doubleValue();
            if (energyDeltaWh <= 0) continue; // skip intervals with no energy consumption

            double energyKWh = energyDeltaWh / 1000.0;

            java.time.LocalTime time = java.time.LocalTime.of(prevTime.getHourOfDay(),
                    prevTime.getMinuteOfHour(), prevTime.getSecondOfMinute());

            Tariff applicableTariff = tariffList.stream()
                    .filter(t -> isWithinTariff(time, t.getStartTime(), t.getEndTime()))
                    .findFirst()
                    .orElse(null);

            if (applicableTariff != null) {
                double unitRate = applicableTariff.getUnitFare();
                double gstRate = applicableTariff.getGstFare();
                double energyCost = energyKWh * unitRate;
                double gstAmount = energyCost * (gstRate / 100.0);
                double intervalCost = energyCost + gstAmount;

                sessionCost = sessionCost.add(BigDecimal.valueOf(intervalCost));

                // Insert interval cost into DB
                ctx.insertInto(TRANSACTION_METER_VALUES)
                        .set(TRANSACTION_METER_VALUES.TRANSACTION_PK, transactionId)
                        .set(TRANSACTION_METER_VALUES.EVENT_TIMESTAMP, currTime)
                        .set(TRANSACTION_METER_VALUES.GST_AMOUNT, gstAmount)
                        .set(TRANSACTION_METER_VALUES.TOTAL_AMOUNT, intervalCost)
                        .execute();

                System.out.printf("[%s] %s to %s | %.3f kWh | ₹%.2f | Tariff: ₹%.2f/unit + %.2f%% GST\n",
                        boxId,
                        prevTime.toString("HH:mm:ss"),
                        currTime.toString("HH:mm:ss"),
                        energyKWh,
                        intervalCost,
                        unitRate,
                        gstRate);
            }
        }

        return sessionCost;
    }

    public boolean isWithinTariff(java.time.LocalTime time, java.time.LocalTime start, java.time.LocalTime end) {
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        } else {
            // Overnight (e.g. 22:00 to 06:00)
            return !time.isBefore(start) || time.isBefore(end);
        }
    }
}


