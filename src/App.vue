<template>
    <div id="app">
        <div class="container">
            <nav class="navbar navbar-expand-lg navbar-light">
                <router-link :to="{ name: 'home' }" class="navbar-brand">&#9998; Составь слова</router-link>
                <button class="navbar-toggler" type="button" data-toggle="collapse" data-target="#navbarSupportedContent" aria-controls="navbarSupportedContent" aria-expanded="false" aria-label="Toggle navigation">
                    <span class="navbar-toggler-icon"></span>
                </button>

                <div class="collapse navbar-collapse" id="navbarSupportedContent">
                    <ul class="nav navbar-nav ml-auto">
                        <li class="nav-item">
                            <router-link to="/game" class="nav-link">Game</router-link>
                        </li>
                        <li class="nav-item">
                            <router-link to="/about" class="nav-link">About</router-link>
                        </li>     
                        <li class="nav-item">
                            <router-link to="/student/498" class="nav-link">Student</router-link>
                        </li>  
                        <li class="nav-item dropdown">
                            <a class="nav-link dropdown-toggle" href="#" id="navbarServices" role="button" data-toggle="dropdown" aria-haspopup="true" aria-expanded="false">
                              Сервисы
                            </a>
                            <div class="dropdown-menu" aria-labelledby="navbarServices">
                                <router-link to="/service/answers" class="dropdown-item">Поиск слова</router-link>
                                <router-link to="/service/description" class="dropdown-item">Значение слова</router-link>
                             </div>
                        </li>
                        <li class="nav-item">
                            <router-link to="/redirect" class="nav-link">Redirect</router-link>
                        </li> 
                    </ul>
                </div>
            </nav>  
        </div>   
        <div class="container">
            <router-view></router-view>
        </div> 
    </div>
</template>

<script>
export default {

}
</script>

<style scoped>
nav.navbar {
    background-color: #e3f2fd;
}
</style>