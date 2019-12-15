package org.rx.test.bean;

public interface HttpUserManager {
    HttpUserManager INSTANCE = new HttpUserManager() {
        @Override
        public int computeInt(int x, int y) {
            return x + y;
        }
    };

    int computeInt(int x, int y);
}
