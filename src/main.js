import Vue from 'vue'
import App from './App.vue'
import VueRouter from 'vue-router'
import 'bootstrap'
import 'bootstrap/dist/css/bootstrap.min.css'
import '@fortawesome/fontawesome-free/css/all.css'
import '@fortawesome/fontawesome-free/js/all.js'
import VModal from 'vue-js-modal'
 
import routes from './routes';

Vue.config.productionTip = false;

Vue.use(VueRouter);
Vue.use(VModal);

const router = new VueRouter({mode: 'history', routes});


new Vue({
    router,
    render: h => h(App),
}).$mount('#app')
