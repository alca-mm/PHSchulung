package de.internal.awareness.tracking;

import org.springframework.stereotype.Component;

/**
 * Standard-Implementierung von {@link TrackingTokenFactory}: erzeugt kryptografisch starke
 * 256-Bit-Zufallstokens ueber {@link TrackingTokens#generate()}.
 */
@Component
class DefaultTrackingTokenFactory implements TrackingTokenFactory {

    @Override
    public TrackingTokens.GeneratedToken newToken() {
        return TrackingTokens.generate();
    }
}
