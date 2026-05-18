package examplemods.fabricautoreg;

import org.admany.quantified.api.QuantifiedAPI;

import java.util.concurrent.CompletableFuture;

public final class FabricAutoRegisterCaller {

    private FabricAutoRegisterCaller() {}

    public static CompletableFuture<String> submitSimpleTask() {
        return QuantifiedAPI.<String>compute("fabricAutoTask")
            .submit(() -> "ok");
    }
}
