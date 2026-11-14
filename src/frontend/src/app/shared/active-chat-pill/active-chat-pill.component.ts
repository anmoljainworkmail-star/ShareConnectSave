import { Component } from '@angular/core';
import { Observable } from 'rxjs';

import { ActiveChat, ActiveChatService } from '../../features/chat/services/active-chat.service';

// Presentational only (Single Responsibility): every decision about WHETHER a
// chat is resumable lives in ActiveChatService; this just draws it. That is
// also why the same pill can sit on the classic radar and the 3D map radar
// without either screen knowing anything about chat -- the screens stay
// scan-focused, and "return to my conversation" is one reusable piece of
// shared UI.
//
// The async pipe subscribes and unsubscribes for us, so there is no
// ngOnDestroy / takeUntil bookkeeping here at all.
@Component({
  selector: 'app-active-chat-pill',
  templateUrl: './active-chat-pill.component.html',
  styleUrl: './active-chat-pill.component.css',
  // Module-based project (no standalone components) -- see UserCardComponent.
  standalone: false
})
export class ActiveChatPillComponent {
  readonly activeChat$: Observable<ActiveChat | null>;

  constructor(activeChatService: ActiveChatService) {
    this.activeChat$ = activeChatService.activeChat$;
  }

  label(chat: ActiveChat): string {
    return chat.unreadCount > 0
      ? `Return to your chat, ${chat.unreadCount} unread ${chat.unreadCount === 1 ? 'message' : 'messages'}`
      : 'Return to your chat';
  }

  badgeText(chat: ActiveChat): string {
    return chat.unreadCount > 9 ? '9+' : String(chat.unreadCount);
  }
}
