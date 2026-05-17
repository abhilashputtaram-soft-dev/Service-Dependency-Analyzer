package com.defendermate.graph.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceMetadata {

    private String service;
    private String team;
    private String tier;
    private String region;
}
