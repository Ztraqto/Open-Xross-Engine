package com.ztraqto.openxross.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.certification.CertificationProfile;
import com.ztraqto.openxross.api.certification.CertificationProgram;
import com.ztraqto.openxross.api.certification.CertificationAccess;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.database.XrossDbRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Shared, plugin-neutral registry for named user certification programs. */
public final class CertificationService implements IService {
    private static final String NAMESPACE = "certifications";
    private static final String PROGRAMS = "programs";
    private static final String PROFILES = "profiles";
    private final XrossDbClient database;
    private final ObjectMapper mapper = new ObjectMapper();

    public CertificationService(XrossDbClient database) {
        this.database = database;
    }

    @Override public void init(XrossEngine engine) { CertificationAccess.install(this); }
    @Override public void shutdown() { CertificationAccess.clear(this); }
    @Override public String getName() { return "CertificationService"; }

    public CertificationProgram upsertProgram(CertificationProgram program) {
        database.write(new XrossDbKey(NAMESPACE, PROGRAMS, program.id()), programPayload(program), XrossDbClient.ANY_REVISION);
        return program;
    }

    public Optional<CertificationProgram> getProgram(String programId) {
        return database.read(new XrossDbKey(NAMESPACE, PROGRAMS, programId)).map(record -> readProgram(record.payload()));
    }

    public CertificationProfile grant(String programId, long userId, String displayName, String detail, long grantedBy) {
        if (getProgram(programId).isEmpty()) throw new IllegalArgumentException("Certification program does not exist: " + programId);
        CertificationProfile profile = new CertificationProfile(programId, userId, displayName, detail, System.currentTimeMillis(), grantedBy);
        database.write(profileKey(programId, userId), profilePayload(profile), XrossDbClient.ANY_REVISION);
        return profile;
    }

    public boolean revoke(String programId, long userId) {
        return database.delete(profileKey(programId, userId), XrossDbClient.ANY_REVISION);
    }

    /** The lightweight eligibility check intended for other plugins. */
    public boolean hasCertification(long userId, String programId) {
        return getProfile(userId, programId).isPresent();
    }

    public Optional<CertificationProfile> getProfile(long userId, String programId) {
        return database.read(profileKey(programId, userId)).map(record -> readProfile(record.payload()));
    }

    public List<CertificationProfile> getProfiles(long userId) {
        List<CertificationProfile> profiles = getProfiles();
        profiles.removeIf(profile -> profile.userId() != userId);
        profiles.sort(Comparator.comparingLong(CertificationProfile::grantedAt));
        return List.copyOf(profiles);
    }

    public List<CertificationProfile> getProfiles() {
        List<CertificationProfile> profiles = new ArrayList<>();
        String after = null;
        do {
            var page = database.scanPage(NAMESPACE, PROFILES, after, 100);
            for (XrossDbRecord record : page.records()) {
                CertificationProfile profile = readProfile(record.payload());
                profiles.add(profile);
            }
            after = page.nextKey();
        } while (after != null);
        return profiles;
    }

    public List<CertificationProgram> getPrograms() {
        List<CertificationProgram> programs = new ArrayList<>();
        String after = null;
        do {
            var page = database.scanPage(NAMESPACE, PROGRAMS, after, 100);
            for (XrossDbRecord record : page.records()) programs.add(readProgram(record.payload()));
            after = page.nextKey();
        } while (after != null);
        programs.sort(Comparator.comparing(CertificationProgram::name));
        return List.copyOf(programs);
    }

    private XrossDbKey profileKey(String programId, long userId) {
        return new XrossDbKey(NAMESPACE, PROFILES, programId + "-" + userId);
    }

    private ObjectNode programPayload(CertificationProgram program) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", program.id());
        node.put("name", program.name());
        node.put("description", program.description());
        node.put("badgeLabel", program.badgeLabel());
        node.put("badgeIconUrl", program.badgeIconUrl());
        return node;
    }

    private ObjectNode profilePayload(CertificationProfile profile) {
        ObjectNode node = mapper.createObjectNode();
        node.put("programId", profile.programId());
        node.put("userId", profile.userId());
        node.put("displayName", profile.displayName());
        node.put("detail", profile.detail());
        node.put("grantedAt", profile.grantedAt());
        node.put("grantedBy", profile.grantedBy());
        return node;
    }

    private CertificationProgram readProgram(JsonNode node) {
        return new CertificationProgram(node.path("id").asText(), node.path("name").asText(), node.path("description").asText(),
                node.path("badgeLabel").asText(), node.path("badgeIconUrl").asText());
    }

    private CertificationProfile readProfile(JsonNode node) {
        return new CertificationProfile(node.path("programId").asText(), node.path("userId").asLong(), node.path("displayName").asText(),
                node.path("detail").asText(), node.path("grantedAt").asLong(), node.path("grantedBy").asLong());
    }
}
