import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { ChatComponent } from './chat.component';

// forChild: lazy-loaded under the root router's guarded 'chat' route
// (app-routing.module.ts). The param is the CONNECTION id -- that is what
// ConnectionFlowService.openChat and ChatFlowService navigate with; the Mongo
// chat id is resolved from the "ChatOpened" push (see ChatSignalrService).
const routes: Routes = [{ path: ':id', component: ChatComponent }];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class ChatRoutingModule {}
