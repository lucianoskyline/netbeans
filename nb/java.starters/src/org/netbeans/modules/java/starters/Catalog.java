/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.java.starters;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

/**
 * What a remote generator offers: platform versions and selectable
 * dependencies (Spring Initializr) or extensions (code.quarkus.io).
 */
final class Catalog {

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static final class Version {
        final String id;
        final String label;

        Version(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static final class Item {
        final String id;
        final String name;
        final String group;
        final String description;

        Item(String id, String name, String group, String description) {
            this.id = id;
            this.name = name;
            this.group = group;
            this.description = description == null ? "" : description;
        }
    }

    final List<Version> versions = new ArrayList<>();
    Version defaultVersion;
    final List<Item> items = new ArrayList<>();

    private Catalog() {
    }

    static String get(String url, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", accept) // NOI18N
                .header("User-Agent", "NetBeans-Java-Starters") // NOI18N
                .GET().build();
        HttpResponse<String> r = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + r.statusCode() + " from " + url); // NOI18N
        }
        return r.body();
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Spring Initializr metadata: Boot versions and dependency groups. */
    static Catalog spring() throws IOException, InterruptedException {
        JSONObject d = (JSONObject) JSONValue.parse(get("https://start.spring.io/", // NOI18N
                "application/vnd.initializr.v2.2+json")); // NOI18N
        Catalog c = new Catalog();
        JSONObject boot = (JSONObject) d.get("bootVersion"); // NOI18N
        String def = (String) boot.get("default"); // NOI18N
        for (Object o : (JSONArray) boot.get("values")) { // NOI18N
            JSONObject v = (JSONObject) o;
            Version version = new Version((String) v.get("id"), (String) v.get("name")); // NOI18N
            c.versions.add(version);
            if (version.id.equals(def)) {
                c.defaultVersion = version;
            }
        }
        for (Object g : (JSONArray) ((JSONObject) d.get("dependencies")).get("values")) { // NOI18N
            JSONObject group = (JSONObject) g;
            for (Object o : (JSONArray) group.get("values")) { // NOI18N
                JSONObject dep = (JSONObject) o;
                c.items.add(new Item((String) dep.get("id"), (String) dep.get("name"), // NOI18N
                        (String) group.get("name"), (String) dep.get("description"))); // NOI18N
            }
        }
        return c;
    }

    /** code.quarkus.io platform streams (versions). */
    static Catalog quarkusStreams() throws IOException, InterruptedException {
        Catalog c = new Catalog();
        for (Object o : (JSONArray) JSONValue.parse(get("https://code.quarkus.io/api/streams", "application/json"))) { // NOI18N
            JSONObject s = (JSONObject) o;
            String key = (String) s.get("key"); // NOI18N
            String label = key.substring(key.indexOf(':') + 1);
            if (Boolean.TRUE.equals(s.get("recommended"))) { // NOI18N
                label += " (recommended)"; // NOI18N
            }
            Version v = new Version(key, "Quarkus " + label); // NOI18N
            c.versions.add(v);
            if (Boolean.TRUE.equals(s.get("recommended"))) { // NOI18N
                c.defaultVersion = v;
            }
        }
        if (c.defaultVersion == null && !c.versions.isEmpty()) {
            c.defaultVersion = c.versions.get(0);
        }
        return c;
    }

    /** Extensions of one Quarkus stream, platform extensions only (the curated set). */
    static List<Item> quarkusExtensions(String streamKey) throws IOException, InterruptedException {
        List<Item> items = new ArrayList<>();
        String url = "https://code.quarkus.io/api/extensions/stream/" + enc(streamKey) + "?platformOnly=true"; // NOI18N
        for (Object o : (JSONArray) JSONValue.parse(get(url, "application/json"))) { // NOI18N
            JSONObject e = (JSONObject) o;
            Object cat = e.get("category"); // NOI18N
            String group = cat instanceof JSONObject ? (String) ((JSONObject) cat).get("name") : String.valueOf(cat); // NOI18N
            items.add(new Item((String) e.get("id"), (String) e.get("name"), group, (String) e.get("description"))); // NOI18N
        }
        return items;
    }
}
