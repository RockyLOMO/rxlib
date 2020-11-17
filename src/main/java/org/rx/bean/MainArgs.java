package org.rx.bean;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.Map;

@SuppressWarnings("WeakerAccess")
@Data
@AllArgsConstructor
public class MainArgs {
    private ArrayList<String> operations;
    private Map<String, String> options;
}
