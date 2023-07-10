package org.rx;

import java.util.Arrays;

public class LoopTest {
    public static void main(String[] args) throws Exception {
        int[] array = new int[1000];
        fill(array);
        System.out.println(Arrays.stream(array).sum());
    }

    static void fill(int[] array) {
        int reps = array.length;

        int i = 0;
        while (++i < reps) {
            array[i] = i * i;
        }
    }
}
