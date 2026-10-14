package com.raully.psxpsp.core;

/** Interface comum de ciclo de vida. Ainda não executa emulação. */
public interface EmulationCore {
    String systemName();
    void loadGame(String uri) throws UnsupportedOperationException;
    void start() throws UnsupportedOperationException;
    void pause();
    void stop();
}
