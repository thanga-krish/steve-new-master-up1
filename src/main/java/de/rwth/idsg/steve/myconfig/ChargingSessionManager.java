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
import de.rwth.idsg.steve.ocpp.OcppTransport;
import de.rwth.idsg.steve.ocpp.OcppVersion;
import de.rwth.idsg.steve.ocpp.task.RemoteStartTransactionTask;
import de.rwth.idsg.steve.ocpp.task.RemoteStopTransactionTask;
import de.rwth.idsg.steve.repository.dto.ChargePointSelect;
import de.rwth.idsg.steve.service.ChargePointHelperService;
import de.rwth.idsg.steve.service.ScheduledChargingServiceup;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStartTransactionParams;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStopTransactionParams;
import ocpp.cs._2015._10.RegistrationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.Optional;

@Service
public class ChargingSessionManager {

    @Autowired
    private ChargePointService16_InvokerImpl cpsImpl;
    @Autowired
    private ScheduledChargingServiceup chargingServiceup;
    @Autowired
    private ChargePointHelperService chargePointHelperService;

    public void startSession(String chargeBoxId, int connectorId, String idTag) {
        Optional<RegistrationStatus> statusOpt = chargePointHelperService.getRegistrationStatus(chargeBoxId);

        if (statusOpt.isPresent()) {
            RegistrationStatus status = statusOpt.get();

            boolean isOnline = chargePointHelperService.isOnline(chargeBoxId);

            if (status == ocpp.cs._2015._10.RegistrationStatus.ACCEPTED && isOnline) {

                RemoteStartTransactionParams params = new RemoteStartTransactionParams();
                params.setIdTag(idTag);
                params.setConnectorId(connectorId);

                ChargePointSelect cp = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);
                RemoteStartTransactionTask task = new RemoteStartTransactionTask(OcppVersion.V_16, params);

                cpsImpl.remoteStartTransaction(cp, task);
                chargingServiceup.sendUserAlert(idTag, new Contents("Charging session started successfully."));
            } else {
                chargingServiceup.sendUserAlert(idTag,
                        new Contents("Cannot start charging. The device is offline"));
            }
        } else {
            chargingServiceup.sendUserAlert(idTag,
                    new Contents("Cannot start charging. Device is not registered."));
        }
    }

    public void stopSession(String chargeBoxId, int connectorId, Integer transactionId, String idtag) {
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
        chargingServiceup.sendUserAlert(idtag, new Contents("Schedule completed successfully."));
    }
}
