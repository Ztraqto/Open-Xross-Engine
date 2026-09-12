package com.ztraqto.openxross.core.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ztraqto.openxross.api.bus.PluginData;
import com.ztraqto.openxross.api.bus.PluginHandler;
import com.ztraqto.openxross.api.plugin.XrossPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PluginBus {

    private static final Logger logger = LoggerFactory.getLogger(PluginBus.class);
    private final ObjectMapper mapper = new ObjectMapper();

    // チャンネル名 -> リスナー情報のリスト
    private final Map<String, List<ListenerReg>> listeners = new ConcurrentHashMap<>();

    private record ListenerReg(XrossPlugin plugin, Method method) {}

    /**
     * プラグイン内の @PluginHandler メソッドをスキャンして登録する。
     * (XrossPluginの初期化時に自動で呼ばれるようにする)
     */
    public void register(XrossPlugin plugin) {
        for (Method method : plugin.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(PluginHandler.class)) {
                if (method.getParameterCount() != 1) {
                    logger.warn("Invalid @PluginHandler in {}: Method must have exactly 1 parameter.", plugin.getMeta().getName());
                    continue;
                }

                Class<?> paramType = method.getParameterTypes()[0];
                PluginData annotation = paramType.getAnnotation(PluginData.class);
                
                if (annotation == null) {
                    logger.warn("Invalid @PluginHandler in {}: Parameter class must have @PluginData.", plugin.getMeta().getName());
                    continue;
                }

                String channel = annotation.value();
                listeners.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(new ListenerReg(plugin, method));
                logger.debug("Registered bus listener: {} -> {} # {}", channel, plugin.getMeta().getName(), method.getName());
            }
        }
    }

    /**
     * 登録解除 (プラグインUnload時)
     */
    public void unregister(XrossPlugin plugin) {
        listeners.values().forEach(list -> list.removeIf(reg -> reg.plugin() == plugin));
    }

    /**
     * データを送信する (Fire and Forget)
     * 戻り値は期待しないイベント通知用。
     */
    public void post(Object packet) {
        PluginData info = packet.getClass().getAnnotation(PluginData.class);
        if (info == null) {
            throw new IllegalArgumentException("Packet must have @PluginData annotation.");
        }

        String channel = info.value();
        List<ListenerReg> regs = listeners.get(channel);

        if (regs == null || regs.isEmpty()) return;

        // ClassLoaderの壁を超えるため、一度JSON文字列にする
        String jsonPayload;
        try {
            jsonPayload = mapper.writeValueAsString(packet);
        } catch (Exception e) {
            logger.error("Failed to serialize packet", e);
            return;
        }

        for (ListenerReg reg : regs) {
            try {
                // 受信側のクラス定義に合わせてJSONを復元 (Duck Typing)
                Class<?> targetType = reg.method().getParameterTypes()[0];
                Object targetPacket = mapper.readValue(jsonPayload, targetType);

                // メソッド実行
                reg.method().setAccessible(true);
                reg.method().invoke(reg.plugin(), targetPacket);
                
            } catch (Exception e) {
                logger.error("Error dispatching to plugin: " + reg.plugin().getMeta().getName(), e);
            }
        }
    }
    
    /**
     * データを送信し、返信を受け取る (Request-Response)
     * WriderSQLのように「送って、結果を受け取る」スタイル。
     * ※ 最初の1つの応答だけを返す（早い者勝ち）。
     */
    public <T> T ask(Object packet, Class<T> responseType) {
        PluginData info = packet.getClass().getAnnotation(PluginData.class);
        if (info == null) throw new IllegalArgumentException("Packet must have @PluginData annotation.");

        String channel = info.value();
        List<ListenerReg> regs = listeners.get(channel);

        if (regs == null || regs.isEmpty()) return null;

        try {
            String jsonPayload = mapper.writeValueAsString(packet);

            // 登録されているリスナーを順に試し、最初に非nullを返したものを採用
            for (ListenerReg reg : regs) {
                Class<?> targetParamType = reg.method().getParameterTypes()[0];
                Object targetPacket = mapper.readValue(jsonPayload, targetParamType);

                reg.method().setAccessible(true);
                Object result = reg.method().invoke(reg.plugin(), targetPacket);

                if (result != null) {
                    // 結果を要求された型に変換して返す (ここでもJSON経由で安全に変換)
                    String jsonResult = mapper.writeValueAsString(result);
                    return mapper.readValue(jsonResult, responseType);
                }
            }
        } catch (Exception e) {
            logger.error("Error in ask() request", e);
        }
        return null;
    }
}
