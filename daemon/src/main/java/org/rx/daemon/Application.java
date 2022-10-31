package org.rx.daemon;

import java.util.Scanner;

public class Application {
    public static void main(String[] args) {
//        Scanner scanner = new Scanner(System.in);
//        while (scanner.hasNextLine()) {
//            String line = scanner.nextLine();
//            switch (line) {
//                case "0":
//                    System.out.println(System.currentTimeMillis());
//                    break;
//            }
//        }
        System.out.print(System.currentTimeMillis());
        System.out.print(",");
        System.out.print(System.nanoTime());
        System.out.flush();
    }
}
