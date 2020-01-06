
import Home from './components/Home.vue';
import NotFound from './components/NotFound.vue';
import About from './components/About.vue';
import Rules from './components/Rules.vue';
import Game from './components/Game.vue';
import Answers from './components/Answers.vue';
import Description from './components/Description.vue';

const routes = [
    { path: '', component: Home, name: 'init' },
    { path: '/', component: Home, name: 'home', },
    { path: '*', component: NotFound },
    { path: '/about/game', component: About },
    { path: '/about/rules', component: Rules },
    { path: '/game', component: Game },
    { path: '/service/answers', component: Answers },
    { path: '/service/description', component: Description },
];

export default routes;