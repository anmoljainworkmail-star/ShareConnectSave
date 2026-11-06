import { Component, OnInit } from '@angular/core';

import { ConnectionFlowService } from './connections/connection-flow.service';
import { ChatFlowService } from './features/chat/services/chat-flow.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  standalone: false,
  styleUrl: './app.component.css'
})
export class AppComponent implements OnInit {
  constructor(
    private readonly connectionFlowService: ConnectionFlowService,
    private readonly chatFlowService: ChatFlowService
  ) {}

  ngOnInit(): void {
    // The one place the always-on connection flow is started (T067): the
    // root component outlives every route, so the SignalR connection and its
    // reactions (banner, sound, navigate-to-chat) are alive on every screen.
    // It follows the login state itself -- connecting after sign-in, tearing
    // down on logout -- so nothing else needs to call connect()/disconnect().
    this.connectionFlowService.start();
    // T068: same pattern for the chat hub -- ChatOpened must pull the user
    // into the chat from any screen, ChatClosed into the rating screen.
    this.chatFlowService.start();
  }
}
