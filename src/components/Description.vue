<template>
    <div>

<h4>Значение слова</h4>

<div v-if="error" class="alert alert-danger alert-dismissible fade show" role="alert">
  Произошла ошибка при загрузке данных
  <button type="button" class="close" data-dismiss="alert" aria-label="Close">
    <span aria-hidden="true">&times;</span>
  </button>
</div>

<div class="alert alert-secondary well" role="alert">
    <p>
        <span>Введите слово, значение которого вы хотите узнать</span>
    </p>
    <form class="form-inline" method="get">
        <div class="form-group">
            <input type="text" class="form-control form-control-lg margin-right" placeholder="" name="word" v-model="word" maxlength="30" autocomplete="off">
        </div>
        <button class="btn btn-primary btn-lg" v-on:click.stop.prevent="onSubmit">Поиск</button>
    </form>
</div> 

        
<template v-if="descriptions && descriptions.length">
<blockquote v-for="(description, idx) of descriptions" v-bind:key="idx">
    <span class="badge badge-secondary">{{ description.vocab }}</span> - {{ description.def }}
    <template v-if="description.baseform">
        <br><span class="descr">{{ description.baseform }}</span>
    </template> 
    <template v-if="description.phongl">
        <br><span class="descr">{{ description.phongl }}</span>
    </template> 
    <template v-if="description.grclassgl">
        <br><span class="descr">{{ description.grclassgl }}</span>
    </template>
    <template v-if="description.stylgl">
        <br><span class="descr">{{ description.stylgl }}</span>
    </template> 
    <template v-if="description.anti">
        <br><span class="descr">противоп. <i>{{ description.anti }}</i></span>
    </template>        
    <template v-if="description.leglexam">
        <br><i>{{ description.leglexam }}</i>
    </template>         
</blockquote>        
</template>        
        

    </div>
</template>

<script>

import axios from 'axios';

export default {
    data: function() {
        return {
            word: '',
            descriptions: [],
            posts: [],
            error: false
        }
    },
    methods: {
        onSubmit: function () {   
            axios.get('http://combination-words.local/description/' + encodeURI(this.word))
            .then(response => {
                this.descriptions = response.data.data;
                this.error = false;
            })
            .catch(e => {
                this.error = e;
            });
        }
    }    
}
</script>

<style scoped>
blockquote {
    padding: 5px 20px;
    margin: 0 0 20px;
    border-left: 5px solid #eee;
}
</style>