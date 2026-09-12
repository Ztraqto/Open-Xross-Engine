package com.ztraqto.openxross.core.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.storage.IDatabaseOwner;
import com.ztraqto.openxross.api.wrider.WriderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WriderSQL {

    private static final Logger logger = LoggerFactory.getLogger(WriderSQL.class);

    private final XrossDbClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public WriderSQL(XrossDbClient client) {
        this.client = client;
    }

    public void initTable(IDatabaseOwner owner, Class<?> type) {
        requireOwner(owner);
        getIdField(type);
    }

    public boolean saveQL(IDatabaseOwner owner, Object entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity is required.");
        }

        try {
            Class<?> type = entity.getClass();
            Object id = getIdValue(type, entity);
            XrossDbKey key = createKey(owner, type, id);
            JsonNode payload = mapper.valueToTree(entity);
            client.write(key, payload);
            return true;
        } catch (Exception exception) {
            logger.error("WriderSQL save failed for {}", entity.getClass().getSimpleName(), exception);
            return false;
        }
    }

    public <T> T loadQL(IDatabaseOwner owner, Class<T> type, Object id) {
        try {
            XrossDbKey key = createKey(owner, type, id);
            return client.read(key)
                    .map(record -> convert(record.payload(), type))
                    .orElse(null);
        } catch (Exception exception) {
            logger.error("WriderSQL load failed for {}", type.getSimpleName(), exception);
            return null;
        }
    }

    public <T> List<T> loadAllQL(IDatabaseOwner owner, Class<T> type) {
        try {
            String namespace = requireOwner(owner).getDatabaseContextId();
            String collection = getCollectionName(type);
            List<T> entities = new ArrayList<>();
            client.scan(namespace, collection).forEach(record -> entities.add(convert(record.payload(), type)));
            return List.copyOf(entities);
        } catch (Exception exception) {
            logger.error("WriderSQL scan failed for {}", type.getSimpleName(), exception);
            return Collections.emptyList();
        }
    }

    public boolean deleteQL(IDatabaseOwner owner, Class<?> type, Object id) {
        try {
            return client.delete(createKey(owner, type, id));
        } catch (Exception exception) {
            logger.error("WriderSQL delete failed for {}", type.getSimpleName(), exception);
            return false;
        }
    }

    private <T> T convert(JsonNode payload, Class<T> type) {
        try {
            return mapper.treeToValue(payload, type);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Could not deserialize " + type.getName(), exception);
        }
    }

    private XrossDbKey createKey(IDatabaseOwner owner, Class<?> type, Object id) {
        if (id == null) {
            throw new IllegalArgumentException("Wrider ID must not be null for " + type.getName());
        }
        String namespace = requireOwner(owner).getDatabaseContextId();
        return new XrossDbKey(namespace, getCollectionName(type), String.valueOf(id));
    }

    private static IDatabaseOwner requireOwner(IDatabaseOwner owner) {
        if (owner == null) {
            throw new IllegalArgumentException("Database owner is required.");
        }
        XrossDbKey.validateName(owner.getDatabaseContextId(), "databaseContextId");
        return owner;
    }

    private static Object getIdValue(Class<?> type, Object entity) throws IllegalAccessException {
        Field idField = getIdField(type);
        idField.setAccessible(true);
        return idField.get(entity);
    }

    private static Field getIdField(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(WriderId.class)) {
                    return field;
                }
            }
        }
        throw new IllegalArgumentException("No @WriderId found in " + type.getName());
    }

    private static String getCollectionName(Class<?> type) {
        return "wrider_" + type.getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }
}
