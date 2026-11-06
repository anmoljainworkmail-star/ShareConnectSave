import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { SharedModule } from '../shared/shared.module';
import { AuthRoutingModule } from './auth-routing.module';
import { LoginComponent } from './login/login.component';
import { OtpVerifyComponent } from './otp-verify/otp-verify.component';

// Feature module (project-wide Angular rule: every component/directive/pipe
// belongs to an NgModule, no standalone components). AuthService, AuthGuard
// and AuthInterceptor are plain @Injectable()s with providedIn: 'root' (or
// root-registered), so they deliberately do not appear in this module at
// all -- only the two screens that are genuinely feature-module-local live
// here. SharedModule is imported for <app-ambient-background>, used by both
// screens' templates.
@NgModule({
  declarations: [LoginComponent, OtpVerifyComponent],
  imports: [CommonModule, FormsModule, AuthRoutingModule, SharedModule]
})
export class AuthModule {}
