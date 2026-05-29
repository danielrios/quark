package com.quark.telegram;

import com.quark.telegram.TelegramMessages.GetUpdatesResponse;
import com.quark.telegram.TelegramMessages.SendMessage;
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
    void sendMessage(SendMessage message);
}
