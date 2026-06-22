package com.cyc.cyctest.agent.domain;

import java.util.List;

public final class DomainModels {
    private DomainModels() {
    }

    public record SubDomainCandidate(
            String subDomainCode,
            String subDomainName,
            String description
    ) {
    }

    public record DomainCandidate(
            String domainCode,
            String domainName,
            String description,
            List<SubDomainCandidate> subDomains
    ) {
    }
}
