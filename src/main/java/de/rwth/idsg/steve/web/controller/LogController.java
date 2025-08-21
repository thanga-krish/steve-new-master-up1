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

import de.rwth.idsg.steve.myconfig.LogEntry;
import de.rwth.idsg.steve.utils.LogFileRetriever;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static jooq.steve.db.tables.AppLog.APP_LOG;

/**
 * @author Sevket Goekay <sevketgokay@gmail.com>
 * @since 15.08.2014
 */
@Slf4j
@Controller
@RequestMapping(value = "/manager")
public class LogController {

    @Autowired
    private DSLContext ctx;

    // ✅ Serves log content from app_log table to JSP
    @GetMapping("/view-db-log")
    public String viewLogs(Model model) {
        List<LogEntry> logs = ctx.selectFrom(APP_LOG)
                .orderBy(APP_LOG.ID.desc())
                .limit(100)
                .fetchInto(LogEntry.class);

        model.addAttribute("logs", logs);
        return "logs"; // Resolved as /WEB-INF/views/logs.jsp
    }

    @GetMapping("/log")
    public void log(HttpServletResponse response) {
        response.setContentType("text/plain");

        try (PrintWriter writer = response.getWriter()) {
            Optional<Path> pathOptional = LogFileRetriever.INSTANCE.getPath();

            if (pathOptional.isPresent()) {
                Path logPath = pathOptional.get();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        Files.newInputStream(logPath), StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.println(line);
                    }

                } catch (MalformedInputException e) {
                    writer.write("⚠️ Error: Log file contains invalid or non-UTF-8 characters.\n");
                    writer.write("Suggestion: Open manually or delete the corrupted log.\n");
                    log.error("MalformedInputException while reading log file", e);
                } catch (IOException e) {
                    writer.write("⚠️ Error reading log file.\n");
                    log.error("IOException while reading log file", e);
                }

            } else {
                writer.write(LogFileRetriever.INSTANCE.getErrorMessage());
            }

        } catch (IOException e) {
            log.error("IOException while streaming log file to response", e);
        }
    }

    public String getLogFilePath() {
        return LogFileRetriever.INSTANCE.getLogFilePathOrErrorMessage();
    }

}
