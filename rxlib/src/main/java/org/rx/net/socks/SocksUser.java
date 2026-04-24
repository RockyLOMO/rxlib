package org.rx.net.socks;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.core.Strings;

import java.io.Serializable;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class SocksUser implements Serializable {
    private static final long serialVersionUID = 7845976131633777320L;
    public static final SocksUser ANONYMOUS = new SocksUser();

    final String username;
    String password;

    public boolean isAnonymous() {
        return Strings.hashEquals(ANONYMOUS.getUsername(), username);
    }

    public SocksUser() {
        username = "anonymous";
    }
}
