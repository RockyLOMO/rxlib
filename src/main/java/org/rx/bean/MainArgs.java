package org.rx.bean;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

@SuppressWarnings("WeakerAccess")
@RequiredArgsConstructor
@Data
public final class MainArgs {
    final List<String> operations;
    final Map<String, String> options;
}
