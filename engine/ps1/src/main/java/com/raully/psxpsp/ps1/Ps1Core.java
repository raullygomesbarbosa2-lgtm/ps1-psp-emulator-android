package com.raully.psxpsp.ps1;

import com.raully.psxpsp.core.EmulationCore;

/** Esqueleto do futuro núcleo PS1 próprio. HLE e dispositivos ainda não implementados. */
public final class Ps1Core implements EmulationCore {
    @Override public String systemName() { return "PlayStation 1"; }
    @Override public void loadGame(String uri) { throw new UnsupportedOperationException("Núcleo PS1 ainda não implementado"); }
    @Override public void start() { throw new UnsupportedOperationException("Núcleo PS1 ainda não implementado"); }
    @Override public void pause() { }
    @Override public void stop() { }
}
