package com.tirso.tirsolador;

import org.json.JSONException;
import org.json.JSONObject;

public final class Game {
    public final String id, title, description, coverName;
    public final Console console;
    public final long telegramAccount,chatId,messageId,fileSize;
    public final int fileId;
    public final String fileName;
    public Game(String id, String title, Console console, String description, String coverName) {
        this(id,title,console,description,coverName,0,0,0,0,"",0);
    }
    public Game(String id,String title,Console console,String description,String coverName,long account,long chat,long message,int file,String fileName,long size) {
        this.id = id; this.title = title; this.console = console;
        this.description = description; this.coverName = coverName;
        telegramAccount=account;chatId=chat;messageId=message;fileId=file;this.fileName=fileName;fileSize=size;
    }
    static Game fromJson(JSONObject j) throws JSONException {
        String id = j.getString("id"), title = j.getString("title");
        if (id.trim().isEmpty() || title.trim().isEmpty() || id.length() > 200 || title.length() > 300)
            throw new JSONException(T.t("ID o título inválido"));
        try {
            return new Game(id, title, Console.valueOf(j.getString("console")),
                    j.optString("description", T.t("Sin descripción")), j.optString("coverName", title),
                    j.optLong("telegramAccount"),j.optLong("chatId"),j.optLong("messageId"),j.optInt("fileId"),j.optString("fileName"),j.optLong("fileSize"));
        } catch (IllegalArgumentException e) { throw new JSONException(T.t("Consola desconocida")); }
    }
    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("title", title).put("console", console.name())
                .put("description", description).put("coverName", coverName)
                .put("telegramAccount",telegramAccount).put("chatId",chatId).put("messageId",messageId)
                .put("fileId",fileId).put("fileName",fileName).put("fileSize",fileSize);
    }
}
