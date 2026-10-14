package com.raully.psxpsp.psp;

import com.raully.psxpsp.core.EmulationCore;

/** Esqueleto do futuro núcleo PSP próprio; não é um emulador funcional. */
public final class PspCore implements EmulationCore {
    @Override public String systemName() { return "PlayStation Portable"; }
    @Override public void loadGame(String uri) { throw new UnsupportedOperationException("Núcleo PSP ainda não implementado"); }
    @Override public void start() { throw new UnsupportedOperationException("Núcleo PSP ainda não implementado"); }
    @Override public void pause() { }
    @Override public void stop() { }
}
