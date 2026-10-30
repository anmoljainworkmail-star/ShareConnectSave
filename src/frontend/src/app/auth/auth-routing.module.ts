import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { LoginComponent } from './login/login.component';
import { OtpVerifyComponent } from './otp-verify/otp-verify.component';

// forChild (not forRoot) -- this module is lazy-loaded by the root router,
// so it registers its own child routes under the /auth prefix the root
// router already matched, rather than redeclaring the whole app's route
// table.
const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: 'otp', component: OtpVerifyComponent },
  { path: '', redirectTo: 'login', pathMatch: 'full' }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class AuthRoutingModule {}
