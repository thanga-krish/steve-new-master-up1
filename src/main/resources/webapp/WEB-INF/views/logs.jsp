<%--

    SteVe - SteckdosenVerwaltung - https://github.com/steve-community/steve
    Copyright (C) ${license.git.copyrightYears} SteVe Community Team
    All Rights Reserved.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

--%>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<html>
<head>
    <title>App Log Viewer</title>
    <style>
        table {
            border-collapse: collapse;
            width: 100%;
        }
        th, td {
            padding: 8px;
            border: 1px solid #ddd;
            text-align: left;
        }
    </style>
</head>
<body>
<h2>Websocket Log Viewer</h2>

<!-- 🔍 Filters -->
<form method="get" action="/manager/view-db-log" style="margin-bottom: 20px;">
    <label>Start Date: <input type="date" name="startDate" /></label>
    <label>End Date: <input type="date" name="endDate" /></label>
    <label>ChargeBox ID: <input type="text" name="chargeBoxId" placeholder="Enter ChargeBox ID" /></label>
    <button type="submit">Submit</button>
</form>

<!-- 🔄 Log Table -->
<div id="logTable">
    <table>
        <thead>
        <tr>
            <th>Timestamp(UTC)</th>
            <th>ChargeBox ID</th>
            <th>Transaction ID</th>
            <th>Session ID</th>
            <th>Event</th>
            <th>Payload</th>
            <th>Direction</th>
        </tr>
        </thead>
        <tbody id="logTableBody">
        <c:forEach var="log" items="${logs}">
            <tr>
                <td><c:out value="${log.timestampStr}" /></td>
                <td><c:out value="${log.chargeBoxId}" /></td>
                <td><c:out value="${log.transactionId}" /></td>
                <td><c:out value="${log.sessionId}" /></td>
                <td><c:out value="${log.event}" /></td>
                <td><c:out value="${log.payload}" /></td>
                <td><c:out value="${log.direction}" /></td>
            </tr>
        </c:forEach>
        </tbody>
    </table>
</div>

<!-- 🔁 Auto-refresh Script -->
<script>
    function reloadLogs() {
        fetch(window.location.href)
            .then(response => response.text())
            .then(html => {
                const tempDiv = document.createElement('div');
                tempDiv.innerHTML = html;

                const newBody = tempDiv.querySelector('#logTableBody');
                if (newBody) {
                    document.getElementById('logTableBody').innerHTML = newBody.innerHTML;
                }
            })
            .catch(err => console.error('Failed to reload logs:', err));
    }

    setInterval(reloadLogs, 1000); // Auto-refresh every 1 sec
</script>
</body>
</html>
