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

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CustomStopReasonStore {
    private final Map<Integer, String> reasonMap = new ConcurrentHashMap<>();

    public void putReason(Integer transactionId, String reason) {
        reasonMap.put(transactionId, reason);
    }

    public String getAndRemoveReason(Integer transactionId) {
        return reasonMap.remove(transactionId); // remove after reading to avoid memory leak
    }

    public boolean hasReason(Integer transactionId) {
        return reasonMap.containsKey(transactionId);
    }
}
