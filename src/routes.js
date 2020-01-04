
import Home from './components/Home.vue';
import NotFound from './components/NotFound.vue';
import About from './components/About.vue';
import Game from './components/Game.vue';
import Student from './components/Student.vue';
import Redirect from './components/Redirect.vue';
import Answers from './components/Answers.vue';
import Description from './components/Description.vue';

const routes = [
    { path: '', component: Home, name: 'init' },
    { path: '/', component: Home, name: 'home', },
    { path: '*', component: NotFound },
    { path: '/about', component: About },
    { path: '/game', component: Game },
    { path: '/student/:id', component: Student },    
    { path: '/redirect', component: Redirect },
    { path: '/service/answers', component: Answers },
    { path: '/service/description', component: Description },
];

export default routes;