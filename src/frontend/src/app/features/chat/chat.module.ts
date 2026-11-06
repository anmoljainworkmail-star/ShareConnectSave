import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ChatRoutingModule } from './chat-routing.module';
import { ChatComponent } from './chat.component';

// Feature module for the chat screen (project Angular Rules: no standalone
// components, feature modules lazy-loaded). Only what is screen-local is
// declared here: ChatComponent.
//
// The services (ChatSignalrService, ChatFlowService, ChatApiService) are
// deliberately NOT provided here: they are root-provided singletons because
// the hub connection and the "ChatOpened -> navigate here" reaction must be
// alive from login onward, before this lazy module is ever downloaded -- it is
// the very event that causes this module to be downloaded.
//
// FormsModule only for the message input's two-way binding; nothing else from
// SharedModule is needed (the chat screen draws its own plain background).
@NgModule({
  declarations: [ChatComponent],
  imports: [CommonModule, FormsModule, ChatRoutingModule]
})
export class ChatModule {}
