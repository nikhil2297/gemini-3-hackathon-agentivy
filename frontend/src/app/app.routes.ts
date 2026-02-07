import { Routes } from '@angular/router';
import { HomeComponent } from './pages/home/home.component';
import { ConfigureComponent } from './pages/configure/configure.component';
import { ResultsComponent } from './pages/results/results.component';

export const routes: Routes = [
  {
    path: '',
    component: HomeComponent,
    title: 'AgentIvy - Home',
  },
  {
    path: 'configure',
    component: ConfigureComponent,
    title: 'AgentIvy - Configure',
  },
  {
    path: 'results',
    component: ResultsComponent,
    title: 'AgentIvy - Results',
  },
  {
    path: '**',
    redirectTo: '',
    pathMatch: 'full',
  },
];
