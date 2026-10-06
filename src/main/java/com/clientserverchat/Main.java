package com.clientserverchat;

import com.clientserverchat.client.ClientLauncher;
import com.clientserverchat.server.ServerLauncher;
import java.util.Arrays;

/** One project and entry point; select the server or client role with the first argument. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        String role = args.length == 0 ? "client" : args[0];
        String[] applicationArgs = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        switch (role.toLowerCase(java.util.Locale.ROOT)) {
            case "server" -> ServerLauncher.main(applicationArgs);
            case "client" -> ClientLauncher.main(applicationArgs);
            default -> throw new IllegalArgumentException("Cách chạy: Main [server|client]. Mặc định: client.");
        }
    }
}
