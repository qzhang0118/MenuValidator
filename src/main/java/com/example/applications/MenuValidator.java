package com.example.applications;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Fetches menus from server and checks if they are valid.
 */
public class MenuValidator {

    private static class MenuOutput {
        @SerializedName("valid_menus")
        public List<MenuItem> validMenus = new ArrayList<MenuItem>();
        @SerializedName("invalid_names")
        public List<MenuItem> invalidMenus = new ArrayList<MenuItem>();
    }

    private static class MenuItem {
        @SerializedName("root_id")
        public int rootId;
        public List<Integer> children;

        public MenuItem(int rootId, List<Integer> children) {
            this.rootId = rootId;
            this.children = children;
        }
    }

    private static final String QUESTION_MARK = "?";
    private static final String AMPERSAND = "&";
    private static final String QUERY_PARAM_PAGE = "page=";

    private final Gson gson = new Gson();
    private final JsonParser jsonParser = new JsonParser();

    private int totalMenuCount;
    private int maxMenuCountPerPage;

    // Key is the id of a menu, value is the ids of its children.
    private Map<Integer, List<Integer>> menuToChildrenMap = new HashMap<Integer, List<Integer>>();
    // All ids of menus that do not have parents.
    private List<Integer> rootMenuIds = new ArrayList<Integer>();
    private List<Map<Integer, List<Integer>>> validMenuList = new ArrayList<Map<Integer, List<Integer>>>();
    private List<Map<Integer, List<Integer>>> invalidMenuList = new ArrayList<Map<Integer, List<Integer>>>();

    /**
     * Fetches all menus and start the validation.
     */
    private void fetchAllMenusAndValidate(URL baseUrl) throws IOException {
        int totalPages = -1;
        int pageNum = 0;
        do {
            pageNum++;
            fetchMenus(new URL(assembleUrlForPage(baseUrl, pageNum)));

            if (totalPages == -1) {
                totalPages = totalMenuCount / maxMenuCountPerPage + (totalMenuCount % maxMenuCountPerPage == 0 ? 0 : 1);
            }
        } while (pageNum < totalPages);

        validateMenus(menuToChildrenMap, rootMenuIds);
    }

    /**
     * Assembles a url for fetching menus based on page number and base URL.
     */
    private static String assembleUrlForPage(URL baseUrl, int pageNum) {
        String urlStr = baseUrl.toString();
        return String.format("%s%s%s",
                urlStr,
                urlStr.contains(QUESTION_MARK) ? AMPERSAND : QUESTION_MARK,
                QUERY_PARAM_PAGE + pageNum);
    }

    /**
     * Fetches menus from the server.
     *
     * @param url the url to fetch menus from
     * @throws IOException when there is issue making requests
     */
    private void fetchMenus(URL url) throws IOException {
        // Use StringBuffer if we want to make this run in parallel.
        StringBuilder jsonStrBuilder = new StringBuilder();

        // Send HTTP request to the given URL.
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.connect();

        int httpResponseCode = conn.getResponseCode();

        // Check for error status.
        if (httpResponseCode != 200) {
            throw new RuntimeException("Status code is " + httpResponseCode + " when requesting " + url);
        }

        Scanner scanner = new Scanner(conn.getInputStream());
        while (scanner.hasNext()) {
            jsonStrBuilder.append(scanner.nextLine());
        }
        scanner.close();

        parseResponse(jsonStrBuilder.toString());
    }

    /**
     * Parses json string returned from server.
     */
    private void parseResponse(String jsonStr) {
        JsonObject jsonObject = jsonParser.parse(jsonStr).getAsJsonObject();
        JsonArray jsonArray = jsonObject.get("menus").getAsJsonArray();
        if (jsonArray == null) {
            return;
        }

        JsonObject pageJson = jsonObject.get("pagination").getAsJsonObject();

        // For calculating number of pages later.
        totalMenuCount = pageJson.get("total").getAsInt();
        maxMenuCountPerPage = pageJson.get("per_page").getAsInt();

        for (int i = 0; i < jsonArray.size(); i++) {
            List<Integer> childrenIds = new ArrayList<Integer>();
            JsonObject menu = jsonArray.get(i).getAsJsonObject();

            int currId = menu.get("id").getAsInt();
            if (menu.get("parent_id") == null) {
                rootMenuIds.add(currId);
            }

            if (menu.get("child_ids") != null) {
                JsonArray childrenIdsJson = menu.get("child_ids").getAsJsonArray();
                for (int j = 0; j < childrenIdsJson.size(); j++) {
                    childrenIds.add(childrenIdsJson.get(j).getAsInt());
                }
                menuToChildrenMap.put(currId, childrenIds);
            }
        }
    }

    /**
     * Validates paths for all root menu ids.
     */
    private void validateMenus(Map<Integer, List<Integer>> menuToChildrenMap, List<Integer> rootMenuIds) {
        for (int i = 0; i < rootMenuIds.size(); i++) {
            checkMenuId(true, rootMenuIds.get(i), rootMenuIds.get(i),
                    menuToChildrenMap, new ArrayList<Integer>());
        }
    }

    /**
     * Checks paths from the root menu id, outputs each path into a list of map with key as root menu id and value
     * as the path, depending on if the path is valid without cycle.
     *
     * @param isValidMenu flags if the path for the root menu id is valid or not.
     */
    private void checkMenuId(boolean isValidMenu, int rootMenuId, int currId,
                             Map<Integer, List<Integer>> menuToChildrenMap, List<Integer> path) {
        List<Integer> childrenIds = menuToChildrenMap.get(currId);
        // Conditions: 1. the current menu id has child; 2. the current menu id is not the same as root menu id (no
        // cycle in the path) or the current id is the same with root menu id at the beginning when path is empty.
        if (!childrenIds.isEmpty() && ((rootMenuId == currId && path.isEmpty()) || rootMenuId != currId)) {
            for (int i = 0; i < childrenIds.size(); i++) {
                if (path.contains(childrenIds.get(i))) {
                    isValidMenu = false;
                }
                path.add(childrenIds.get(i));
                checkMenuId(isValidMenu, rootMenuId, childrenIds.get(i), menuToChildrenMap, new ArrayList<Integer>(path));
                path.remove(path.size() - 1);
            }
        }
        // Condition: the current menu id has no child (the end of path) or the current menu id is the same as root menu
        // id (there is cycle in the path).
        else {
            if (rootMenuId == currId) {
                isValidMenu = false;
            }
            // Puts into map with key as the root menu id, the path as value; adds the map into a list depending on if
            // the path is valid.
            Map<Integer, List<Integer>> rootPathMap = new HashMap<Integer, List<Integer>>();
            rootPathMap.put(rootMenuId, new ArrayList<Integer>(path));
            if (isValidMenu) {
                validMenuList.add(rootPathMap);
            } else {
                invalidMenuList.add(rootPathMap);
            }
        }
    }

    public String outputResult() {
        MenuOutput menuOutput = new MenuOutput();
        for (int i = 0; i < validMenuList.size(); i++) {
            for (int key : validMenuList.get(i).keySet()) {
                menuOutput.validMenus.add(new MenuItem(key, validMenuList.get(i).get(key)));
            }
        }
        for (int i = 0; i < invalidMenuList.size(); i++) {
            for (int key : invalidMenuList.get(i).keySet()) {
                menuOutput.invalidMenus.add(new MenuItem(key, invalidMenuList.get(i).get(key)));
            }
        }
        return gson.toJson(menuOutput);
    }

    public static void main(String args[]) throws IOException {
        MenuValidator menuValidator = new MenuValidator();
        URL url = new URL("https://backend-challenge-summer-2018.herokuapp.com/challenges.json?id=1");
        menuValidator.fetchAllMenusAndValidate(url);
        // Extra Challenge with different id in url.
        // URL urlExtraChallenge = new URL("https://backend-challenge-summer-2018.herokuapp.com/challenges.json?id=2");
        // menuValidator.fetchAllMenusAndValidate(urlExtraChallenge);
        System.out.println(menuValidator.outputResult());
    }
}
