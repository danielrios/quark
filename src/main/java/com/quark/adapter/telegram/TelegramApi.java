package com.quark.adapter.telegram;

import com.quark.adapter.telegram.TelegramMessages.EditMessageText;
import com.quark.adapter.telegram.TelegramMessages.GetUpdatesResponse;
import com.quark.adapter.telegram.TelegramMessages.SendMessage;
import com.quark.adapter.telegram.TelegramMessages.SendMessageResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "telegram")
public interface TelegramApi {

    @GET
    @Path("/getUpdates")
    GetUpdatesResponse getUpdates(@QueryParam("offset") long offset, @QueryParam("timeout") int timeout);

    @POST
    @Path("/sendMessage")
    SendMessageResponse sendMessage(SendMessage message);

    @POST
    @Path("/editMessageText")
    void editMessageText(EditMessageText edit);
}
