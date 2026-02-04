import { Routes } from '@angular/router';
import { HomeComponent } from './pages/home/home.component';
import { ConfigureComponent } from './pages/configure/configure.component';
import { ResultsComponent } from './pages/results/results.component';

export const routes: Routes = [
  {
    path: '',
    component: HomeComponent,
    title: 'GitHub Component Tester - Home',
  },
  {
    path: 'configure',
    component: ConfigureComponent,
    title: 'Configure Testing - GitHub Component Tester',
  },
  {
    path: 'results',
    component: ResultsComponent,
    title: 'Test Results - GitHub Component Tester',
  },
  {
    path: '**',
    redirectTo: '',
    pathMatch: 'full',
  },
];
