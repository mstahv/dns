package com.example.dns.service;

import java.util.List;

public record CompetitionInfo(
        String eventId,
        String eventTitle,
        String discipline,
        String startListUrl
) {

    public static CompetitionInfo fromJson(java.util.Map<String, Object> json) {
        String eventId = (String) json.get("EventID");
        String eventTitle = (String) json.get("EventTitle");
        String discipline = (String) json.get("Discipline");

        String startListUrl = null;
        Object hyperLinks = json.get("HyperLinks");
        if (hyperLinks instanceof List<?> links) {
            for (Object link : links) {
                if (link instanceof java.util.Map<?, ?> linkMap) {
                    if ("IOFStartListXML".equals(linkMap.get("Type"))) {
                        startListUrl = (String) linkMap.get("Url");
                        break;
                    }
                }
            }
        }

        return new CompetitionInfo(eventId, eventTitle, discipline, startListUrl);
    }
}
