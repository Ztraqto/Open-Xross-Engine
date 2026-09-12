package com.ztraqto.openxross.api.runtime;

public interface XrossRuntime {
    void start() throws Exception;
    void stop();
    boolean isRunning();
}
