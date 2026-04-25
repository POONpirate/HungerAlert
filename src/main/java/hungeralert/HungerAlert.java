package hungeralert;

import necesse.engine.modLoader.annotations.ModEntry;

@ModEntry
public class HungerAlert {

    public void init() {
        System.out.println("[HungerAlert] Loaded! A pulsing red border will appear when hunger drops below 10%.");
    }
}
