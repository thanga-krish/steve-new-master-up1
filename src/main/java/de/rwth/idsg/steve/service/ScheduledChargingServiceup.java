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

import com.fasterxml.jackson.databind.ObjectMapper;
import de.rwth.idsg.steve.myconfig.*;
import de.rwth.idsg.steve.ocpp.ChargePointService16_InvokerImpl;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.joda.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static jooq.steve.db.Tables.*;
import static jooq.steve.db.Tables.TRANSACTION_START;
import static jooq.steve.db.Tables.TRANSACTION_STOP;
import static org.jooq.impl.DSL.*;

@Slf4j
@Service
public class ScheduledChargingServiceup {

    @Autowired
    private DSLContext ctx;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private CustomStopReasonStore customStopReasonStore;
    @Autowired
    private ChargePointService16_InvokerImpl cpsImpl;
    @Autowired
    private ChargingSessionManager chargingSessionManager;

    private final ExecutorService executor = Executors.newFixedThreadPool(20);
    private final Map<String, String> lastScheduleTimes = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 1000)
    public void checkScheduledCharging() {
        List< ScheduleChargingAPI> schedules = fetchScheduledChargingFromApi();
        DateTime nowUtc = DateTime.now(DateTimeZone.UTC).withMillisOfSecond(0);

        List<ScheduleChargingAPI> validSchedules = schedules.stream()
                .filter(s -> {
                    DateTime endUtc = safeParseISTtoUTC(s.getEnd_time());
                    return !endUtc.isBefore(nowUtc);
                })
                .toList();

        for (ScheduleChargingAPI schedule : validSchedules) {
            DateTime startUtc = safeParseISTtoUTC(schedule.getStart_time());
            DateTime endUtc   = safeParseISTtoUTC(schedule.getEnd_time());

            String key = schedule.getCharger_id() + "-" + schedule.getIdtag();
            String currentTime = schedule.getStart_time() + "-" + schedule.getEnd_time();

            if (!currentTime.equals(lastScheduleTimes.get(key))) {
                lastScheduleTimes.put(key, currentTime);
                executor.submit(() -> sendReminder(schedule));
            }

            if (Math.abs(nowUtc.getMillis() - startUtc.getMillis()) < 1000) {
                executor.submit(() -> startDevice(schedule));
            }
            if (Math.abs(nowUtc.getMillis() - endUtc.getMillis()) < 1000) {
                executor.submit(() -> stopDevice(schedule));
            }
        }
    }

    private void startDevice(ScheduleChargingAPI schedule) {
        try {
            int connectorId = Integer.parseInt(schedule.getCon());

            Integer connectorPk = ctx.select(CONNECTOR.CONNECTOR_PK)
                    .from(CONNECTOR)
                    .where(CONNECTOR.CHARGE_BOX_ID.eq(schedule.getCharger_id()))
                    .and(CONNECTOR.CONNECTOR_ID.eq(connectorId))
                    .fetchOneInto(Integer.class);

            String status = ctx.select(CONNECTOR_STATUS.STATUS)
                    .from(CONNECTOR_STATUS)
                    .where(CONNECTOR_STATUS.CONNECTOR_PK.eq(connectorPk))
                    .orderBy(CONNECTOR_STATUS.STATUS_TIMESTAMP.desc())
                    .limit(1)
                    .fetchOneInto(String.class);

            Contents contents = null;

            switch(status) {
                case "Preparing" -> {
                    chargingSessionManager.startSession(schedule.getCharger_id(), connectorId, schedule.getIdtag());
                }
                case "Available" -> contents = new Contents("Device is online. Please connect the charger to vehicle.");
                case "Finishing" -> contents = new Contents("Device just finished charging. Try again later.");
                case "Unavailable" -> contents = new Contents("Device is offline. Please check the charger.");
                default -> contents = new Contents("Device status unknown. Please check the charger.");
            }
            if (contents != null) {
                sendUserAlert(schedule.getIdtag(), contents);
            }

        } catch (Exception e) {
            System.err.println("Error in startDevice: " + e.getMessage());
        }
    }

    private void stopDevice(ScheduleChargingAPI schedule) {
        try {
            int connectorId = Integer.parseInt(schedule.getCon());
            String idtag = schedule.getIdtag();
            Integer transactionId = ctx.select(TRANSACTION_START.TRANSACTION_PK)
                    .from(TRANSACTION_START)
                    .join(CONNECTOR)
                    .on(TRANSACTION_START.CONNECTOR_PK.eq(CONNECTOR.CONNECTOR_PK))
                    .where(CONNECTOR.CHARGE_BOX_ID.eq(schedule.getCharger_id()))
                    .and(TRANSACTION_START.ID_TAG.eq(schedule.getIdtag()))
                    .andNotExists(
                            selectOne().from(TRANSACTION_STOP)
                                    .where(TRANSACTION_STOP.TRANSACTION_PK.eq(TRANSACTION_START.TRANSACTION_PK))
                    )
                    .orderBy(TRANSACTION_START.START_TIMESTAMP.desc())
                    .limit(1)
                    .fetchOneInto(Integer.class);

            if (transactionId != null) {
                chargingSessionManager.stopSession(schedule.getCharger_id(), connectorId, transactionId, idtag);
                customStopReasonStore.putReason(transactionId, "SchedulerStop");

            } else {
                log.warn("No active transaction found for {} - {}", schedule.getCharger_id(), connectorId);
            }
        } catch (Exception e) {
            log.error("Error stopping session for {} - {}", schedule.getCharger_id(), schedule.getCon(), e);
        }
    }

    private DateTime safeParseISTtoUTC(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) return null;
        DateTimeFormatter formatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(DateTimeZone.forID("Asia/Kolkata"));
        return formatter.parseDateTime(dateTimeStr).withZone(DateTimeZone.UTC);
    }

    private void sendReminder(ScheduleChargingAPI schedule) {
        String msg = String.format("You have scheduled from %s to %s",
                schedule.getStart_time(), schedule.getEnd_time());
        log.info("Sending reminder: {}", msg);
    }

    public void sendUserAlert(String idTag, Contents contents) {
        try {
            Filter filter = new Filter("tag", "idTag", "=", "123");

            NotificationPayload payload = new NotificationPayload();
            payload.setApp_id("cc37d72a-cf45-40f6-97c4-d5e91ae0a0c6");
            payload.setFilters(Collections.singletonList(filter));
            payload.setHeading(new Heading("Alert!"));
            payload.setContents(contents);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Basic os_v2_app_zq35okwpivapnf6e2xurvyfayz5qhwv7o7subv42ua5zplqcfzq2csqcq7dolpy3gmoj6dk52hljxmdplgdpoknh5w4wgxif2e3zhia");

            HttpEntity<NotificationPayload> entity = new HttpEntity<>(payload, headers);

            String url = "https://onesignal.com/api/v1/notifications";

            restTemplate.postForObject(url, entity, String.class);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<ScheduleChargingAPI> fetchScheduledChargingFromApi() {
        String apiUrl = "http://15.207.37.132/auto_charge/tod_start.php";
        try {
            ResponseEntity<String> responseEntity = restTemplate.getForEntity(apiUrl, String.class);
            String body = responseEntity.getBody();

            if (responseEntity.getStatusCode() != HttpStatus.OK || body == null) {
                log.error("Failed to fetch schedules, status: {}", responseEntity.getStatusCode());
                return Collections.emptyList();
            }
            try {
                ObjectMapper mapper = new ObjectMapper();
                ScheduleResponse response = mapper.readValue(body, ScheduleResponse.class);

                return response.getData() != null ? response.getData() : Collections.emptyList();
            } catch (Exception jsonEx) {
                log.warn("API did not return valid JSON. Raw response:\n{}", body);
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("Error while calling schedule API", e);
            return Collections.emptyList();
        }
    }
}









