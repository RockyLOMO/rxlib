package org.rx.net.socks;

public interface SessionAuthenticator extends Authenticator {
    AuthResult authenticate(String username, String password);

    @Override
    default SocksUser login(String username, String password) {
        AuthResult result = authenticate(username, password);
        return result == null ? null : result.getUser();
    }
}
